package com.urlshortener.user;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.user.CreationRateLimiter.Denial;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The creation throttle (issue #7): creation — {@code POST /api/links} and nothing
 * else (D16) — spends one slot of the keyholder's per-key allowance (D15, {@link
 * CreationRateLimiter}); beyond it the request never reaches a controller and
 * answers 429 problem+json with a {@code Retry-After} header. Reads ({@code GET
 * /me}, #5's {@code GET /api/links} when it lands) and the public redirect pass
 * through untouched.
 *
 * <p>Sits after the Bearer seam, so the identity the allowance is keyed by exists —
 * and a garbage key has already failed closed with 401 inside the Bearer filter
 * (D9), consuming no slots. A creation attempt with no identity yet (no header, no
 * session) is not this filter's business either: the authorize rules answer 401
 * downstream. An attempt spends its slot whether the Destination validates or not —
 * the throttle limits creation attempts, the work a key does, not the rows it
 * manages to write. The identity is the keyholder's {@code users.id}, the same one
 * a signed-in session carries, so there is no session bypass of a spent key.
 *
 * <p>Not a bean: constructed once by {@code SecurityConfig} inside the chain (like
 * the Bearer filter), so the servlet container does not also register it globally.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    /** Creation — the one route the throttle applies to (D16). */
    private static final RequestMatcher CREATION = new AntPathRequestMatcher("/api/links", "POST");

    private final CreationRateLimiter creations;

    private final ObjectMapper objectMapper;

    RateLimitFilter(CreationRateLimiter creations, ObjectMapper objectMapper) {
        this.creations = creations;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!CREATION.matches(request)) {
            chain.doFilter(request, response); // not creation: never limited (D16)
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof GitHubPrincipalUser principal)) {
            chain.doFilter(request, response); // no identity yet: the authorize rules answer 401 downstream
            return;
        }
        Optional<Denial> denial = creations.tryAcquireCreationSlot(principal.getUserId());
        if (denial.isPresent()) {
            writeTooManyRequests(response, denial.get().retryAfterSeconds());
            return; // never reaches a controller: no validation, no row
        }
        chain.doFilter(request, response);
    }

    /**
     * The 429 problem+json of a spent allowance (the plan's item 2), carrying the
     * retry signal twice — once for machines ({@code Retry-After}), once for humans
     * (the detail).
     */
    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Short Link creation is limited per API key. Retry after %d seconds."
                        .formatted(retryAfterSeconds));
        problem.setType(URI.create("about:blank"));
        problem.setTitle(HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
