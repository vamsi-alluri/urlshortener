package com.urlshortener.link;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * The Short Link routes (G5): create at {@code POST /api/links}, follow at
 * {@code GET /{slug}}.
 */
@RestController
class ShortLinkController {

    private final ShortLinkService service;

    ShortLinkController(ShortLinkService service) {
        this.service = service;
    }

    /**
     * Creates a Short Link for a Destination (D1) and answers {@code 201} with
     * the Slug, the full short_url, and the Destination. Creation is
     * temporarily open; ticket #4 removes that path.
     */
    @PostMapping("/api/links")
    public ResponseEntity<CreateShortLinkResponse> create(
            @Valid @RequestBody CreateShortLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.destination()));
    }

    /**
     * Sends a follower to the Short Link's Destination with {@code 302 Found}
     * — never a 301, and with no cache headers, so every follow reaches the
     * service and later Clicks stay observable (ADR-0001). An unknown Slug is
     * a {@code 404}.
     */
    @GetMapping("/{slug}")
    public ResponseEntity<Void> follow(@PathVariable("slug") String slug) {
        return service.findLiveDestinationBySlug(slug)
                .map(destination -> ResponseEntity
                        .status(HttpStatus.FOUND)
                        .location(URI.create(destination))
                        .<Void>build())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No Short Link exists for Slug '%s'".formatted(slug)));
    }
}
