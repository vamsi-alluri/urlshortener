package com.urlshortener.user;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.urlshortener.user.ApiKeyService.KeyMatch;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The Bearer seam (D9): a request presenting {@code Authorization: Bearer <key>}
 * authenticates as the keyholder with no session at all. The presented key is hashed and
 * matched against the stored hashes; a valid key sets the request's security context, and
 * a malformed, unknown, or stale key fails closed — 401 problem+json — rather than
 * silently continuing unauthenticated.
 *
 * <p>Requests without the header are untouched: browser sessions behave exactly as issue
 * #2 left them, and unauthenticated access stays exactly what it was ({@code POST
 * /api/links} remains open until #4).
 *
 * <p>Not a bean: constructed once by {@code SecurityConfig} inside the chain, so the
 * servlet container does not also register it globally.
 */
final class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeys;

    private final ProblemDetailAuthenticationEntryPoint unauthorized;

    BearerAuthenticationFilter(ApiKeyService apiKeys, ProblemDetailAuthenticationEntryPoint unauthorized) {
        this.apiKeys = apiKeys;
        this.unauthorized = unauthorized;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presentedKey = bearerKey(request);
        if (presentedKey == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<KeyMatch> match = apiKeys.authenticate(presentedKey);
        if (match.isEmpty()) {
            unauthorized.write(response, "The presented API key is malformed or no longer valid.");
            return;
        }
        KeyMatch keyMatch = match.get();
        // the same principal as a signed-in session, so /me answers identically on either path
        GitHubPrincipalUser principal = GitHubPrincipalUser.of(keyMatch.user(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(new PreAuthenticatedAuthenticationToken(
                principal, keyMatch.apiKey().getKeyHash(), principal.getAuthorities()));
        chain.doFilter(request, response);
    }

    /** The presented Bearer token, or null when the request presents no Bearer credential. */
    private static String bearerKey(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.length() <= BEARER_PREFIX.length()
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length());
    }
}
