package com.collabcase.ai;

public class AiResponseException extends RuntimeException {
    public AiResponseException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiResponseException(String message) {
        super(message);
    }
}
