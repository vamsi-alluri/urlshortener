package com.urlshortener.web;

import java.util.List;
import java.util.Objects;

import com.urlshortener.link.CreateShortLinkResponse;
import com.urlshortener.link.DestinationRule;
import com.urlshortener.link.DestinationValidator;
import com.urlshortener.link.ShortLinkService;
import com.urlshortener.user.GitHubPrincipalUser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The browser surface of issue #19: the home page a signed-in User lands on.
 * A form with a Destination input creates the Short Link through the same
 * {@link ShortLinkService} the API uses — the session User as owner, the same
 * ownership rule the Bearer-authenticated API enforces — and the page makes
 * the API Key discoverable by linking {@code /me/key}, where it is issued and
 * shown once.
 *
 * <p>Server-rendered by Thymeleaf, no JavaScript (D6). {@code GET /} shows the
 * form; {@code POST /shorten} is a session-authenticated form POST, CSRF-checked
 * like every page form (only {@code /api/**} is CSRF-exempt, because an API
 * caller has no session to carry a token). An invalid Destination re-renders
 * the form with the broken rules — the same rules, and the same
 * {@code violations[].type/detail} vocabulary, as #4's {@code 400}s — and
 * answers {@code 400} itself, the same status family the API gives the same
 * mistake.
 */
@Controller
class HomeController {

    private final ShortLinkService shortLinks;
    private final String baseUrl;

    HomeController(ShortLinkService shortLinks, @Value("${app.base-url}") String baseUrl) {
        this.shortLinks = shortLinks;
        this.baseUrl = baseUrl;
    }

    /** The home page: the create form, the link to the key page, the signed-in indicator. */
    @GetMapping("/")
    String home(@AuthenticationPrincipal GitHubPrincipalUser principal, Model model) {
        Objects.requireNonNull(principal, "SecurityConfig authenticates /, so the principal is present");
        model.addAttribute("principal", principal);
        return "home";
    }

    /**
     * Creates a Short Link for the submitted Destination with the session User as
     * owner — the browser twin of {@code POST /api/links} — and re-renders the page
     * with the result: the short_url, plus a copy-able curl/Postman hint in the
     * Bearer scheme, pointing at the key page for the credential. A Destination
     * that breaks #4's rules creates nothing: the form re-renders with every
     * broken rule, together.
     */
    @PostMapping("/shorten")
    String shorten(@RequestParam("destination") String destination,
            @AuthenticationPrincipal GitHubPrincipalUser principal, Model model,
            HttpServletResponse response) {
        Objects.requireNonNull(principal, "SecurityConfig authenticates /shorten, so the principal is present");
        model.addAttribute("principal", principal);

        List<DestinationRule> violations = DestinationValidator.violationsOf(destination);
        if (!violations.isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("destination", destination);
            model.addAttribute("violations", violations.stream().map(Violation::new).toList());
            return "home";
        }

        CreateShortLinkResponse created = shortLinks.create(destination, principal.getUserId());
        model.addAttribute("created", created);
        model.addAttribute("curlHint", curlHintFor(created.destination()));
        return "home";
    }

    /**
     * A broken rule as the form's error list renders it: #4's violation
     * vocabulary, verbatim — the type code, and the detail of what to fix.
     */
    public record Violation(String type, String detail) {

        Violation(DestinationRule rule) {
            this(rule.typeCode(), rule.detail());
        }
    }

    /** The copy-able hint: the same creation over the API seam, in the Bearer scheme. */
    private String curlHintFor(String destination) {
        return """
                curl -X POST %s/api/links \\
                  -H "Authorization: Bearer <your API key>" \\
                  -H "Content-Type: application/json" \\
                  -d '{"destination": "%s"}'""".formatted(baseUrl, destination);
    }
}
