package com.collabcase.importer;

import org.springframework.http.HttpStatus;

public class XmiImportException extends RuntimeException {
    private final HttpStatus status;
    public XmiImportException(HttpStatus status, String message) { super(message); this.status = status; }
    public XmiImportException(HttpStatus status, String message, Throwable cause) { super(message, cause); this.status = status; }
    public HttpStatus getStatus() { return status; }
}
