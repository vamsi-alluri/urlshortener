package com.urlshortener.link;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The core loop (issue #1): creating a Short Link for a Destination, and
 * resolving a live Slug back to its Destination. Deactivation (issue #6) is
 * the one lifecycle change a Short Link has (ADR-0002).
 */
@Service
class ShortLinkService {

    /**
     * With 62^7 (~3.5 trillion) possible Slugs, collisions are near-impossible;
     * a handful of retries keeps creation safe while never reissuing a Slug
     * (ADR-0004).
     */
    private static final int MAX_SLUG_ATTEMPTS = 10;

    /**
     * D13: the owner's list pages at 100 by default, and never returns more
     * than 100 entries.
     */
    private static final int DEFAULT_PAGE_LIMIT = 100;
    private static final int MAX_PAGE_LIMIT = 100;

    private final SlugGenerator slugGenerator;
    private final ShortLinkRepository repository;
    private final String baseUrl;

    ShortLinkService(SlugGenerator slugGenerator, ShortLinkRepository repository,
            @Value("${app.base-url}") String baseUrl) {
        this.slugGenerator = slugGenerator;
        this.repository = repository;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    /**
     * Creates a Short Link binding a fresh random Slug to the Destination on
     * behalf of the creating User — their {@code users.id}, recorded as the
     * link's owner (issue #4) — and returns it with the full short_url.
     */
    CreateShortLinkResponse create(String destination, long ownerUserId) {
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String slug = slugGenerator.next();
            ShortLink link = new ShortLink(slug, destination, String.valueOf(ownerUserId), 0,
                    Instant.now(), null);
            if (repository.insert(link)) {
                return new CreateShortLinkResponse(slug, baseUrl + "/" + slug, destination);
            }
            // Slug taken by a live or Deactivated Short Link: draw again, never reuse.
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not draw an unused Slug after " + MAX_SLUG_ATTEMPTS + " attempts");
    }

    /**
     * Resolves a Slug to its live Destination for followers. A Deactivated
     * Short Link does not resolve (GLOSSARY).
     */
    Optional<String> findLiveDestinationBySlug(String slug) {
        return repository.findLiveBySlug(slug).map(ShortLink::destination);
    }

    /**
     * Records one Click on the Short Link the Slug resolved to — every live
     * follow increments its Click count (D3). Called by the follow path only
     * after the Slug resolved to a live Destination: a 404 (unknown Slug)
     * never reaches here, and a 410 (Deactivated, #6) will not either.
     */
    void recordClick(String slug) {
        repository.incrementClickCount(slug);
    }

    /**
     * Deactivates the User's own Short Link (issue #6, D14) — one-way, no undo:
     * the row stays with {@code deactivated_at} set, so the Slug is never
     * reissued (ADR-0004), and follows answer 410.
     *
     * <p>An unknown Slug and another User's Slug both answer 404 with the same
     * detail, so the response leaks nothing about which Short Links exist. A
     * second deactivation answers 409 — whether it was seen on the read or the
     * write lost the race to a concurrent first one.
     */
    void deactivate(String slug, long ownerUserId) {
        String owner = String.valueOf(ownerUserId);
        ShortLink link = repository.findBySlug(slug).orElse(null);
        if (link == null || !owner.equals(link.owner())) {
            // an unknown Slug and another User's Slug answer identically — no existence leak
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No Short Link exists for Slug '%s'".formatted(slug));
        }
        if (link.deactivatedAt() != null
                || !repository.deactivate(slug, owner, Instant.now())) {
            // already Deactivated on the read, or a concurrent deactivation won the write
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Short Link '%s' is already Deactivated.".formatted(slug));
        }
    }

    /**
     * Whether the Slug is bound to a Deactivated Short Link — the follow path's
     * miss branch (issue #6): a Deactivated Slug answers 410, not 404.
     */
    boolean isDeactivated(String slug) {
        return repository.findBySlug(slug)
                .map(link -> link.deactivatedAt() != null)
                .orElse(false);
    }

    /**
     * Lists the owner's Short Links with their Click counts (D12), one page of
     * D13's pagination, newest first. A null limit or offset takes the default
     * (100 / 0); the limit clamps to at most {@value #MAX_PAGE_LIMIT} and the
     * offset to non-negative. Strictly scoped to the one User — the rows whose
     * owner is their {@code users.id}, never another owner's.
     */
    List<ShortLinkListItem> listForOwner(long ownerUserId, Integer limit, Integer offset) {
        String owner = String.valueOf(ownerUserId);
        int pageLimit = limit == null ? DEFAULT_PAGE_LIMIT : Math.clamp(limit, 0, MAX_PAGE_LIMIT);
        int pageOffset = offset == null ? 0 : Math.max(offset, 0);
        return repository.pageByOwner(owner, pageLimit, pageOffset).stream()
                .map(link -> new ShortLinkListItem(
                        link.slug(),
                        baseUrl + "/" + link.slug(),
                        link.destination(),
                        link.clickCount(),
                        link.createdAt().toString(),
                        link.deactivatedAt() != null))
                .toList();
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
