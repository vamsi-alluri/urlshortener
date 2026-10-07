package com.urlshortener.link;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of the {@code 201} from {@code POST /api/links} (D1). The full
 * {@code short_url} is included so clients never assemble URLs themselves.
 *
 * @param slug     the system-assigned, case-sensitive identifier (D2)
 * @param shortUrl the followable URL, built from the configured base URL
 */
public record CreateShortLinkResponse(
        String slug,
        @JsonProperty("short_url") String shortUrl,
        String destination) {
}
