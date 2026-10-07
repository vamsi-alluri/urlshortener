package com.urlshortener.link;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.util.Locale;

/**
 * Validates that a value is a Destination: a parsable, absolute http or https
 * URL with a host (GLOSSARY). Null, blank, and other schemes are rejected.
 */
final class DestinationValidator implements ConstraintValidator<Destination, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
