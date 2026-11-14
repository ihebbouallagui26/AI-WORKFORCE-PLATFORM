package com.aiworkforce.core.common.exception;

/** Semantically invalid request (400). The message is safe to show to clients. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
