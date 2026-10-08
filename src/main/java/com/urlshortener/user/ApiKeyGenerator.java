package com.urlshortener.user;

import java.security.SecureRandom;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Generates API Keys (D7): {@code ush_} + 32 random base62 characters — ~190 bits of
 * unguessable entropy from {@link SecureRandom}. The {@code ush_} prefix marks the string
 * as this service's secret, a convention secret scanners can match.
 */
@Component
final class ApiKeyGenerator {

    private static final String PREFIX = "ush_";

    private static final int RANDOM_CHARACTERS = 32;

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final Pattern WELL_FORMED = Pattern
            .compile(PREFIX + "[A-Za-z0-9]{" + RANDOM_CHARACTERS + "}");

    private final SecureRandom random = new SecureRandom();

    /** A fresh key: {@code ush_} + 32 random base62 characters. */
    String generate() {
        StringBuilder key = new StringBuilder(PREFIX.length() + RANDOM_CHARACTERS).append(PREFIX);
        for (int character = 0; character < RANDOM_CHARACTERS; character++) {
            key.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return key.toString();
    }

    /** Whether a presented Bearer token has the exact shape of an API Key (D7). */
    static boolean isWellFormed(String candidate) {
        return candidate != null && WELL_FORMED.matcher(candidate).matches();
    }
}
