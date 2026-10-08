package com.urlshortener.user;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * The security surface of issues #2–#4: GitHub OAuth sign-in, a JDBC-backed session,
 * sign-out, the Bearer API Key, and authenticated creation.
 *
 * <p>Everything is open except the User-only surfaces: {@code GET /me} (and the session
 * probe it stands for), the key page {@code /me/key}, and {@code /api/**} — issue #4
 * requires a User to create a Short Link, so every link has an attributable owner; nothing
 * else lives under {@code /api} until #5's key-authenticated list. {@code /api/**} is
 * CSRF-exempt because an API caller has no session to carry a CSRF token. {@code GET /{slug}} stays public. Page paths
 * ({@code /}, {@code /login}, {@code /me}, {@code /me/key}, {@code /logout}) are safe against
 * the slug space: slugs are exactly 7 base62 characters.
 *
 * <p>The Bearer filter (issue #3, D9) sits early in the chain: a request presenting a valid
 * API Key authenticates as the keyholder with no session at all, and a malformed, unknown, or
 * stale key fails closed with 401 problem+json rather than falling back to unauthenticated.
 * Requests without the header are untouched, so browser sessions behave exactly as #2 left
 * them — and a header-less request to a protected API path becomes a 401 problem+json
 * through the entry points below, never a login-page redirect (#4).
 *
 * <p>The GitHub flow is wired only when a registration exists (client id/secret via env —
 * {@code SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID/_CLIENT_SECRET}). Without
 * that configuration the app still boots: sign-in is unavailable, {@code /me} 401s, and the
 * public core loop stays reachable.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            GitHubOAuth2UserService gitHubOAuth2UserService,
            ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint,
            ApiKeyService apiKeys,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository) throws Exception {

        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .addFilterBefore(new BearerAuthenticationFilter(apiKeys, problemDetailAuthenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/me", "/me/key", "/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exception -> exception
                        // API paths answer 401 problem+json — an API caller never gets a
                        // login-page redirect (#2's /me probe, #4's authenticated creation)
                        .defaultAuthenticationEntryPointFor(problemDetailAuthenticationEntryPoint,
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(problemDetailAuthenticationEntryPoint,
                                new AntPathRequestMatcher("/me"))
                        // the key page is for browsers: an unauthenticated Visitor is redirected to
                        // the sign-in page, which itself stays honest when GitHub is unconfigured (#14).
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                new AntPathRequestMatcher("/me/key")))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?signedout")
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION"));

        if (clientRegistrationRepository.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .defaultSuccessUrl("/login")
                    .userInfoEndpoint(userinfo -> userinfo.userService(gitHubOAuth2UserService)));
        }
        return http.build();
    }
}
