package com.collabcase.collaboration;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AwarenessProtocol {

    // Apollon 5.3.0 and its official standalone relay use frame type 3 for Awareness.
    private static final int AWARENESS_FRAME_TYPE = 3;

    private AwarenessProtocol() {
    }

    static void track(String base64Frame, Map<Long, Long> activeClients) {
        try {
            byte[] frame = Base64.getDecoder().decode(base64Frame);
            if (frame.length < 2 || Byte.toUnsignedInt(frame[0]) != AWARENESS_FRAME_TYPE) return;
            Cursor cursor = new Cursor(frame, 1);
            long count = cursor.readVarUint();
            for (long index = 0; index < count; index++) {
                long clientId = cursor.readVarUint();
                long clock = cursor.readVarUint();
                String state = cursor.readString();
                if ("null".equals(state)) activeClients.remove(clientId);
                else activeClients.merge(clientId, clock, Math::max);
            }
        } catch (RuntimeException ignored) {
            // Invalid Awareness metadata must not prevent opaque Yjs relay.
        }
    }

    static int frameType(String base64Frame) {
        try {
            byte[] frame = Base64.getDecoder().decode(base64Frame);
            return frame.length == 0 ? -1 : Byte.toUnsignedInt(frame[0]);
        } catch (IllegalArgumentException exception) {
            return -1;
        }
    }

    static String removalFrame(Map<Long, Long> clients) {
        if (clients.isEmpty()) return null;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(AWARENESS_FRAME_TYPE);
        writeVarUint(payload, clients.size());
        clients.forEach((clientId, clock) -> {
            writeVarUint(payload, clientId);
            writeVarUint(payload, clock + 1);
            writeString(payload, "null");
        });
        return Base64.getEncoder().encodeToString(payload.toByteArray());
    }

    static Map<Long, Long> newClientMap() {
        return new ConcurrentHashMap<>();
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUint(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarUint(ByteArrayOutputStream output, long value) {
        long remaining = value;
        while (remaining > 0x7f) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int position;

        private Cursor(byte[] bytes, int position) {
            this.bytes = bytes;
            this.position = position;
        }

        private long readVarUint() {
            long value = 0;
            int shift = 0;
            while (position < bytes.length && shift <= 63) {
                int current = Byte.toUnsignedInt(bytes[position++]);
                value |= (long) (current & 0x7f) << shift;
                if ((current & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IllegalArgumentException("Invalid varuint");
        }

        private String readString() {
            long length = readVarUint();
            if (length < 0 || length > Integer.MAX_VALUE || position + length > bytes.length) {
                throw new IllegalArgumentException("Invalid string length");
            }
            String value = new String(bytes, position, (int) length, StandardCharsets.UTF_8);
            position += (int) length;
            return value;
        }
    }
}
