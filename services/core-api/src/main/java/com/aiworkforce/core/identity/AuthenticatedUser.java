package com.aiworkforce.core.identity;

import java.util.UUID;

/**
 * The caller of the current request, resolved from the access token's {@code (iss, sub)}.
 * Controllers receive it as a method parameter; it is the only source of identity for services.
 */
public record AuthenticatedUser(UUID userId, String email, boolean emailVerified, String displayName) {
}
