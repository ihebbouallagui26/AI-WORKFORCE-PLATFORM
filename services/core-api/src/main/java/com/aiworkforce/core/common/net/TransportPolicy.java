package com.aiworkforce.core.common.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Pure rule shared by the startup validators of the modules that talk to other services (security fix 8.7): outside the explicit
 * {@code dev} and {@code test} profiles every authenticated outgoing URL must be HTTPS, with no loopback exception, and no URL may carry
 * credentials. Messages name the property, never its value.
 */
public final class TransportPolicy {

    private TransportPolicy() {
    }

    /** @param strict true outside dev/test. */
    public static void validate(Map<String, String> urls, boolean strict) {
        urls.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                return; // reported by the property's own validation
            }
            URI uri;
            try {
                uri = new URI(value.strip());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(name + " is not a valid URL");
            }
            if (uri.getHost() == null || uri.getScheme() == null) {
                throw new IllegalStateException(name + " must be an absolute URL");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalStateException(name + " must not contain credentials");
            }
            if (strict && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException(name + " must use HTTPS outside the dev and test profiles");
            }
        });
    }
}
