package com.urlshortener.user;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * The security surface of issue #2: GitHub OAuth sign-in, a JDBC-backed session, and sign-out.
 *
 * <p>Everything is open except {@code GET /me} (and the session probe it stands for). API
 * authentication arrives with tickets #3/#4 — until then {@code POST /api/links} stays usable
 * without a session, and {@code /api/**} is CSRF-exempt because an API caller has no session to
 * carry a CSRF token. {@code GET /{slug}} stays public. Page paths ({@code /}, {@code /login},
 * {@code /me}, {@code /logout}) are safe against the slug space: slugs are exactly 7 base62
 * characters.
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
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository) throws Exception {

        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/me").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exception -> exception
                        // /me is an API probe: 401 problem+json, not a redirect to the sign-in page.
                        .defaultAuthenticationEntryPointFor(problemDetailAuthenticationEntryPoint,
                                new AntPathRequestMatcher("/me")))
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
