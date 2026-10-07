package com.urlshortener.link;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Draws random Slugs: exactly 7 characters of mixed-case base62 (a-z, A-Z, 0-9).
 * Random and unguessable so the service's Short Links cannot be enumerated;
 * the length can grow later but never shrink (ADR-0004).
 */
@Component
class SlugGenerator {

    /** Committed length per ADR-0004: ~3.5 trillion combinations. */
    static final int SLUG_LENGTH = 7;

    private static final char[] BASE62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    /**
     * Returns a fresh random Slug. A Slug that collides with any existing row —
     * live or Deactivated — is rejected by the caller and drawn again; a used
     * Slug is never reissued.
     */
    String next() {
        char[] slug = new char[SLUG_LENGTH];
        for (int position = 0; position < SLUG_LENGTH; position++) {
            slug[position] = BASE62[random.nextInt(BASE62.length)];
        }
        return new String(slug);
    }
}
