package com.urlshortener.user;

import java.util.Objects;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /me} — the session probe: the signed-in User's GitHub identity as simple JSON, or
 * 401 problem+json when there is no session (issue #2). The key page (ticket #3) builds on it.
 */
@RestController
class MeController {

    record MeResponse(long id, long githubId, String login) {
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal GitHubPrincipalUser principal) {
        Objects.requireNonNull(principal, "SecurityConfig authenticates /me, so the principal is present");
        return new MeResponse(principal.getUserId(), principal.getGithubId(), principal.getLogin());
    }
}
