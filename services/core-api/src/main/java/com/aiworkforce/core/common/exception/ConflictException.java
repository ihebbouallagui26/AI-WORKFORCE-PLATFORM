package com.aiworkforce.core.common.exception;

/**
 * The request is valid but conflicts with the current state (409): duplicate membership,
 * last owner, role in use, invitation already pending... The message is safe to show to clients.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
