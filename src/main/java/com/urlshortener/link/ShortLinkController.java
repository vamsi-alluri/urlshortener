package com.urlshortener.link;

import com.urlshortener.user.GitHubPrincipalUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Short Link routes (G5): create at {@code POST /api/links}, follow at
 * {@code GET /{slug}}. Creation is authenticated (issue #4): {@code SecurityConfig}
 * requires a User on {@code /api/**} — an API Key holder via the Bearer filter, a
 * signed-in session via the OAuth2 login — and the new Short Link is attributed to
 * that User.
 */
@RestController
class ShortLinkController {

    private final ShortLinkService service;

    ShortLinkController(ShortLinkService service) {
        this.service = service;
    }

    /**
     * Creates a Short Link for a Destination (D1) on behalf of the authenticated
     * User and answers {@code 201} with the Slug, the full short_url, and the
     * Destination.
     */
    @PostMapping("/api/links")
    public ResponseEntity<CreateShortLinkResponse> create(
            @Valid @RequestBody CreateShortLinkRequest request,
            @AuthenticationPrincipal GitHubPrincipalUser principal) {
        Objects.requireNonNull(principal,
                "SecurityConfig authenticates POST /api/links, so the principal is present");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request.destination(), principal.getUserId()));
    }

    /**
     * A Destination that breaks issue #4's rules (D10/D11) answers with one
     * {@code 400} problem+json carrying every broken rule: the constraint
     * violations arrive with the rules' type codes, and each is spelled out in
     * the {@code violations} list — {@code invalid_scheme},
     * {@code private_destination}, {@code destination_too_long} — so the caller
     * learns everything to fix in one response.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidDestination(MethodArgumentNotValidException failure) {
        List<Map<String, String>> violations = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> DestinationRule.byTypeCode(error.getDefaultMessage()))
                .filter(Objects::nonNull)
                .map(rule -> {
                    Map<String, String> violation = new LinkedHashMap<>();
                    violation.put("type", rule.typeCode());
                    violation.put("detail", rule.detail());
                    return violation;
                })
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The Destination is not a valid public web URL.");
        problem.setType(URI.create("about:blank"));
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        problem.setProperty("violations", violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * Sends a follower to the Short Link's Destination with {@code 302 Found}
     * — never a 301, and with no cache headers, so every follow reaches the
     * service and later Clicks stay observable (ADR-0001). Each live follow
     * records one Click on the Short Link (D3) after it resolves: an unknown
     * Slug is a {@code 404} and never counts.
     */
    @GetMapping("/{slug}")
    public ResponseEntity<Void> follow(@PathVariable("slug") String slug) {
        return service.findLiveDestinationBySlug(slug)
                .map(destination -> {
                    // after resolution, so only a live follow counts (D3) —
                    // a 404 never reaches here, and a 410 (#6) will not either
                    service.recordClick(slug);
                    return ResponseEntity
                            .status(HttpStatus.FOUND)
                            .location(URI.create(destination))
                            .<Void>build();
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No Short Link exists for Slug '%s'".formatted(slug)));
    }
}
