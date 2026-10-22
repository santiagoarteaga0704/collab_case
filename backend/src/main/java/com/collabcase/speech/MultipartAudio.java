package com.collabcase.speech;

import org.springframework.core.io.ByteArrayResource;

final class MultipartAudio extends ByteArrayResource {
    private final String filename;

    MultipartAudio(byte[] bytes, String filename) {
        super(bytes);
        this.filename = filename;
    }

    @Override
    public String getFilename() {
        return filename;
    }
}
