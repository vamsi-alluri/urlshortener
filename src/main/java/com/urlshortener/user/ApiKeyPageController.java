package com.urlshortener.user;

import java.util.Objects;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The key page (issue #3): the one place a User's API Key is ever shown. The first visit
 * issues the key and displays it — once: only the SHA-256 hash is stored (D8), so no
 * later visit can display it again. The regenerate action — {@code POST /me/key}, a
 * session- and CSRF-authenticated form post — replaces the key and shows the new one
 * once; the previous key stops working the moment the row is replaced.
 *
 * <p>Session-authenticated, like {@code GET /me}; the Bearer key (D9) is the machine
 * path — this page is the human one.
 */
@Controller
class ApiKeyPageController {

    private final ApiKeyService apiKeys;

    ApiKeyPageController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @GetMapping("/me/key")
    String keyPage(@AuthenticationPrincipal GitHubPrincipalUser principal, Model model) {
        Objects.requireNonNull(principal, "SecurityConfig authenticates /me/key, so the principal is present");
        if (!apiKeys.hasKeyFor(principal.getUserId())) {
            // the first visit: issue the key and show it — its only showing
            model.addAttribute("issuedKey", apiKeys.issueFor(principal.getUserId()));
            model.addAttribute("regenerated", false);
        }
        return "key";
    }

    @PostMapping("/me/key")
    String regenerate(@AuthenticationPrincipal GitHubPrincipalUser principal, Model model) {
        Objects.requireNonNull(principal, "SecurityConfig authenticates /me/key, so the principal is present");
        // one click: the previous key is revoked, the new one is shown once
        model.addAttribute("issuedKey", apiKeys.issueFor(principal.getUserId()));
        model.addAttribute("regenerated", true);
        return "key";
    }
}
