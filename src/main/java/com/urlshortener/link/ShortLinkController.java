package com.urlshortener.link;

import com.urlshortener.user.GitHubPrincipalUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The Short Link routes (G5): create at {@code POST /api/links}, deactivate at
 * {@code DELETE /api/links/{slug}} (issue #6), follow at {@code GET /{slug}}.
 * Creation and deactivation are authenticated (issue #4): {@code SecurityConfig}
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
     * Deactivates the authenticated User's own Short Link (issue #6, D14) —
     * one-way, no undo: the Destination stays immutable (ADR-0002) and
     * deactivation is the one lifecycle change a Short Link has. Answers
     * {@code 204} with no body; {@code 404} when the Slug is unknown or
     * belongs to another User — the two answer identically, so nothing leaks
     * about which Short Links exist; {@code 409} when it is already
     * Deactivated. The row stays with {@code deactivated_at} set, so the Slug
     * is never reissued (ADR-0004), and follows answer a bodyless 410.
     */
    @DeleteMapping("/api/links/{slug}")
    public ResponseEntity<Void> deactivate(
            @PathVariable("slug") String slug,
            @AuthenticationPrincipal GitHubPrincipalUser principal) {
        Objects.requireNonNull(principal,
                "SecurityConfig authenticates DELETE /api/links/{slug}, so the principal is present");
        service.deactivate(slug, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Sends a follower to the Short Link's Destination with {@code 302 Found}
     * — never a 301, and with no cache headers, so every follow reaches the
     * service and later Clicks stay observable (ADR-0001). An unknown Slug is
     * a {@code 404}; a Deactivated Short Link is a bodyless {@code 410 Gone}
     * (issue #6, Q16) that never counts a Click.
     */
    @GetMapping("/{slug}")
    public ResponseEntity<Void> follow(@PathVariable("slug") String slug) {
        return service.findLiveDestinationBySlug(slug)
                .map(destination -> ResponseEntity
                        .status(HttpStatus.FOUND)
                        .location(URI.create(destination))
                        .<Void>build())
                .orElseGet(() -> goneIfDeactivated(slug));
    }

    /**
     * The miss branch of follow (issue #6): a Slug bound to a Deactivated
     * Short Link answers a bodyless 410 — one-way, deliberately dead (Q16);
     * a Slug bound to nothing is the 404 it always was.
     */
    private ResponseEntity<Void> goneIfDeactivated(String slug) {
        if (service.isDeactivated(slug)) {
            return ResponseEntity.status(HttpStatus.GONE).<Void>build();
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No Short Link exists for Slug '%s'".formatted(slug));
    }
}
