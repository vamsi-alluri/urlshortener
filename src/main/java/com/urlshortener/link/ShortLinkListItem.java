package com.urlshortener.link;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the owner's list at {@code GET /api/links} (D12): the Short Link
 * with its running Click count. The list is strictly the keyholder's — one
 * owner never sees another's Short Links.
 *
 * @param slug        the system-assigned, case-sensitive identifier (D2)
 * @param shortUrl    the followable URL, built from the configured base URL
 * @param destination the long public web URL the Slug resolves to (ADR-0002: immutable)
 * @param clickCount  the running total of follows of this Short Link (D3): every
 *                    live follow increments it; 404s and 410s never count
 * @param createdAt   when the Short Link was created, ISO-8601 UTC text
 * @param deactivated whether the Short Link is Deactivated — {@code false} while it
 *                    still resolves; the deactivation path itself is ticket #6's
 */
public record ShortLinkListItem(
        String slug,
        @JsonProperty("short_url") String shortUrl,
        String destination,
        @JsonProperty("click_count") long clickCount,
        @JsonProperty("created_at") String createdAt,
        boolean deactivated) {
}
