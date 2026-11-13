package com.aiworkforce.core.common.exception;

/** A dependency (e-mail delivery, identity provider...) is unavailable (503). Message is client-safe. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
