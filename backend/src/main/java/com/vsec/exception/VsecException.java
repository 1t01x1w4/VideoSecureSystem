package com.vsec.exception;

import org.springframework.http.HttpStatus;

/**
 * Business exception whose message is safe to return to the client.
 * All other RuntimeExceptions are treated as internal errors.
 */
public class VsecException extends RuntimeException {

    private final HttpStatus status;

    public VsecException(String message) {
        this(message, HttpStatus.BAD_REQUEST);
    }

    public VsecException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
