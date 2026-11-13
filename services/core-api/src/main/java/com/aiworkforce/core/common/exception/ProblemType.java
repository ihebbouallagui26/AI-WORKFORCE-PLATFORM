package com.aiworkforce.core.common.exception;

import java.net.URI;

/**
 * Stable problem types (RFC 9457 {@code type} member) returned by the API.
 * Clients must branch on {@link #uri()}, never on the human-readable {@code detail}.
 */
public enum ProblemType {
    BAD_REQUEST("bad-request", "Bad request"),
    VALIDATION("validation-error", "Validation failed"),
    UNAUTHORIZED("unauthorized", "Authentication required"),
    FORBIDDEN("forbidden", "Access denied"),
    NOT_FOUND("not-found", "Resource not found"),
    METHOD_NOT_ALLOWED("method-not-allowed", "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", "Unsupported media type"),
    CONFLICT("conflict", "Conflict"),
    SERVICE_UNAVAILABLE("service-unavailable", "Service unavailable"),
    INTERNAL_ERROR("internal-error", "Internal server error");

    private static final String PREFIX = "urn:aiworkforce:problem:";

    private final URI uri;
    private final String title;

    ProblemType(String slug, String title) {
        this.uri = URI.create(PREFIX + slug);
        this.title = title;
    }

    public URI uri() {
        return uri;
    }

    public String title() {
        return title;
    }

    /** Default problem type for an HTTP status code. */
    public static ProblemType forStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }
}
