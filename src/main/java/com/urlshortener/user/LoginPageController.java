package com.urlshortener.user;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The sign-in page (issue #2): the "Sign in with GitHub" link for a Visitor, and the signed-in
 * indicator plus the sign-out action for a User. Server-rendered, no JavaScript (D6).
 *
 * <p>The GitHub link renders only when the flow is actually wired — that is, when a GitHub
 * registration is configured (issue #14: an unconfigured server must not advertise a dead
 * link). Unconfigured, the page says sign-in is unavailable instead.
 */
@Controller
class LoginPageController {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository;

    LoginPageController(ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @GetMapping("/login")
    String login(@AuthenticationPrincipal GitHubPrincipalUser principal,
            @RequestParam(name = "signedout", required = false) String signedOut, Model model) {
        if (principal != null) {
            model.addAttribute("principal", principal);
        }
        model.addAttribute("signedOut", signedOut != null);
        model.addAttribute("signInConfigured", signInConfigured());
        return "login";
    }

    private boolean signInConfigured() {
        ClientRegistrationRepository registrations = clientRegistrationRepository.getIfAvailable();
        if (registrations == null) {
            return false;
        }
        try {
            return registrations.findByRegistrationId("github") != null;
        } catch (IllegalArgumentException noSuchRegistration) {
            return false;
        }
    }
}
