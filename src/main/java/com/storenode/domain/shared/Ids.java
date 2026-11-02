package com.storenode.domain.shared;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randomA = RANDOM.nextLong();
        long randomB = RANDOM.nextLong();

        long mostSignificant = (millis << 16) | 0x7000L | (randomA & 0x0FFFL);   // time | version 7 | 12 random bits
        long leastSignificant = (randomB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;   // variant 10 | 62 random bits
        return new UUID(mostSignificant, leastSignificant);
    }
}

