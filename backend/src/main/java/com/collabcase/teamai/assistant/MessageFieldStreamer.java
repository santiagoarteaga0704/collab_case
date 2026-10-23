package com.collabcase.teamai.assistant;

/**
 * Extracts, progressively, the value of the root-level "mensaje" string field from a JSON
 * document that arrives in chunks. One instance per AI answer.
 */
public class MessageFieldStreamer {
    private enum State { SCAN, KEY, OTHER_STRING, AFTER_KEY, BEFORE_VALUE, VALUE, DONE }

    private static final String FIELD = "mensaje";

    private final StringBuilder emitted = new StringBuilder();
    private final StringBuilder key = new StringBuilder();
    private State state = State.SCAN;
    private int depth;
    private boolean keyPosition;
    private boolean escape;
    private boolean keyEscaped;
    private StringBuilder unicode;
    private boolean valueEscape;

    /** Returns only the text decoded from this chunk. */
    public String feed(String chunk) {
        if (chunk == null) return "";
        int start = emitted.length();
        for (int i = 0; i < chunk.length() && state != State.DONE; i++) {
            step(chunk.charAt(i));
        }
        return emitted.substring(start);
    }

    public String text() {
        return emitted.toString();
    }

    private void step(char c) {
        switch (state) {
            case SCAN -> scan(c);
            case KEY -> {
                boolean wasEscaped = escape;
                if (consumeStringChar(c)) {
                    state = !keyEscaped && key.toString().equals(FIELD) ? State.AFTER_KEY : State.SCAN;
                    keyPosition = false;
                } else if (c == '\\' && !wasEscaped) {
                    keyEscaped = true;
                } else {
                    key.append(c);
                }
            }
            case OTHER_STRING -> {
                if (consumeStringChar(c)) state = State.SCAN;
            }
            case AFTER_KEY -> {
                if (c == ':') state = State.BEFORE_VALUE;
                else if (!Character.isWhitespace(c)) state = State.SCAN;
            }
            case BEFORE_VALUE -> {
                if (c == '"') state = State.VALUE;
                else if (!Character.isWhitespace(c)) state = State.DONE;
            }
            case VALUE -> value(c);
            case DONE -> { }
        }
    }

    private void scan(char c) {
        switch (c) {
            case '{' -> { depth++; keyPosition = depth == 1; }
            case '[' -> { depth++; keyPosition = false; }
            case '}', ']' -> { depth--; keyPosition = false; }
            case ',' -> keyPosition = depth == 1;
            case ':' -> keyPosition = false;
            case '"' -> {
                escape = false;
                if (keyPosition && depth == 1) {
                    key.setLength(0);
                    keyEscaped = false;
                    state = State.KEY;
                } else {
                    state = State.OTHER_STRING;
                }
            }
            default -> { }
        }
    }

    /** Tracks escapes inside a string; returns true when the closing quote arrives. */
    private boolean consumeStringChar(char c) {
        if (escape) {
            escape = false;
            return false;
        }
        if (c == '\\') {
            escape = true;
            return false;
        }
        return c == '"';
    }

    private void value(char c) {
        if (unicode != null) {
            unicode.append(c);
            if (unicode.length() == 4) {
                try {
                    emitted.append((char) Integer.parseInt(unicode.toString(), 16));
                } catch (NumberFormatException ignored) {
                    // malformed escape: dropped
                }
                unicode = null;
            }
            return;
        }
        if (valueEscape) {
            valueEscape = false;
            switch (c) {
                case 'n' -> emitted.append('\n');
                case 't' -> emitted.append('\t');
                case 'r' -> emitted.append('\r');
                case 'b' -> emitted.append('\b');
                case 'f' -> emitted.append('\f');
                case 'u' -> unicode = new StringBuilder();
                default -> emitted.append(c);
            }
            return;
        }
        if (c == '\\') valueEscape = true;
        else if (c == '"') state = State.DONE;
        else emitted.append(c);
    }
}
