package com.aiworkforce.core.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Identifier generation. UUID version 7 (RFC 9562): time-ordered for index locality,
 * 74 random bits so identifiers are not guessable or enumerable.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long timestamp = System.currentTimeMillis() & 0xFFFF_FFFF_FFFFL;
        long mostSignificant = (timestamp << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
