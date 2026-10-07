package com.urlshortener.link;

/**
 * Body of {@code POST /api/links}: the Destination to bind behind a new Short
 * Link. The Destination is immutable once set (ADR-0002).
 */
public record CreateShortLinkRequest(@Destination String destination) {
}
