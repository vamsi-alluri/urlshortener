package com.urlshortener.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The sign-in page (issue #2): the "Sign in with GitHub" link for a Visitor, and the signed-in
 * indicator plus the sign-out action for a User. Server-rendered, no JavaScript (D6).
 */
@Controller
class LoginPageController {

    @GetMapping("/login")
    String login(@AuthenticationPrincipal GitHubPrincipalUser principal,
            @RequestParam(name = "signedout", required = false) String signedOut, Model model) {
        if (principal != null) {
            model.addAttribute("principal", principal);
        }
        model.addAttribute("signedOut", signedOut != null);
        return "login";
    }
}
