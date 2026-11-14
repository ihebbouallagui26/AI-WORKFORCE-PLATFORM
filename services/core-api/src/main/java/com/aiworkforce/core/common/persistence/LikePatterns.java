package com.aiworkforce.core.common.persistence;

import java.util.Locale;

/**
 * Builds safe, case-insensitive {@code LIKE} patterns from user input. Use the result with
 * {@code escape '!'} in JPQL (a backslash escape is mangled by Spring Data's query rewriting).
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    /** {@code %text%} lowercased with LIKE wildcards escaped, or null for blank input. */
    public static String contains(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
