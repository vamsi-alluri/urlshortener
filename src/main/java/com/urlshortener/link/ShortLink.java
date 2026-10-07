package com.urlshortener.link;

import java.time.Instant;

/**
 * A published redirect binding a Slug to a fixed Destination (GLOSSARY).
 * Persisted in the {@code links} table (V1__links.sql).
 *
 * @param slug          the system-assigned, case-sensitive identifier (ADR-0004)
 * @param destination   the long public web URL the Slug resolves to (ADR-0002: immutable)
 * @param owner         the creating User, null until ticket #4 fills it
 * @param clickCount    running total of follows of this Short Link; ticket #5 increments it
 * @param createdAt     when the Short Link was created
 * @param deactivatedAt when the Short Link was Deactivated; null while it still resolves
 */
public record ShortLink(
        String slug,
        String destination,
        String owner,
        long clickCount,
        Instant createdAt,
        Instant deactivatedAt) {
}
