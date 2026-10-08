package com.urlshortener.link;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import com.urlshortener.user.GitHubPrincipalUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The owner's list (issue #5): {@code GET /api/links} answers the keyholder's
 * own Short Links with their Click counts, one page of D13's pagination.
 *
 * <p>The route rides issue #4's gate unchanged — {@code SecurityConfig} demands a
 * User on {@code /api/**} and the Bearer filter authenticates the keyholder — so a
 * request without a valid key is a {@code 401} problem+json before a list is ever
 * built, and the list is strictly that one User's (D12).
 */
@RestController
class OwnerLinksController {

    private final ShortLinkService service;

    OwnerLinksController(ShortLinkService service) {
        this.service = service;
    }

    /**
     * One page of the owner's Short Links, newest first (D12): every entry carries
     * the Short Link with its running Click count and its {@code deactivated} flag
     * ({@code false} everywhere until #6 lands). The {@code limit} and {@code offset}
     * query parameters page the list (D13) — defaulting to 100 and 0, with the limit
     * clamping to at most 100.
     */
    @GetMapping("/api/links")
    public List<ShortLinkListItem> list(
            @AuthenticationPrincipal GitHubPrincipalUser principal,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        Objects.requireNonNull(principal,
                "SecurityConfig authenticates GET /api/links, so the principal is present");
        return service.listForOwner(principal.getUserId(), limit, offset);
    }

    /**
     * A {@code limit} or {@code offset} that is not an integer cannot be a page
     * request: {@code 400} problem+json — the house error contract (README: every
     * error is RFC 7807) — naming the parameter that broke.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> malformedPagination(MethodArgumentTypeMismatchException failure) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The '%s' query parameter must be an integer.".formatted(failure.getName()));
        problem.setType(URI.create("about:blank"));
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
