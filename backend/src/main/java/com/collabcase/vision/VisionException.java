package com.collabcase.vision;

import org.springframework.http.HttpStatus;

public class VisionException extends RuntimeException {
    private final HttpStatus status;

    public VisionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public VisionException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
