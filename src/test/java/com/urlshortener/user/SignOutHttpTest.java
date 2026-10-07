package com.urlshortener.user;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing out really kills the session (issue #2): {@code POST /logout} invalidates the
 * server-side session, so the old session cookie no longer signs anyone in — a shared machine
 * does not stay signed in, and the logout is not a client-side cookie trick.
 */
class SignOutHttpTest extends AbstractGitHubSignInHttpTest {

    private static final Path DATABASE_FILE = newSqliteFile();
    private static final MockWebServer GIT_HUB = startMockGitHub();

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registerTestProperties(registry, DATABASE_FILE, GIT_HUB);
    }

    @AfterAll
    static void shutDown() {
        tearDown(GIT_HUB, DATABASE_FILE);
    }

    @Test
    void signingOutKillsTheSession() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB,
                "{\"id\":777,\"login\":\"hubot\",\"name\":\"Hubot\",\"email\":\"hubot@example.com\"}");
        HttpClient client = noRedirects();

        // the signed-in indicator shows on the page
        Exchange loginPage = get(client, appUrl("/login"), signIn.sessionCookie());
        assertThat(loginPage.status()).isEqualTo(200);
        assertThat(loginPage.body()).contains("Signed in as").contains("hubot");

        // the sign-out form carries the CSRF token (server-rendered, no JavaScript)
        String csrfToken = csrfTokenFrom(loginPage.body());

        // the sign-out action
        Exchange signOut = postForm(client, appUrl("/logout"), Map.of("_csrf", csrfToken), signIn.sessionCookie());
        assertThat(signOut.status()).isEqualTo(302);
        assertThat(signOut.location()).endsWith("/login?signedout");

        // the old session cookie no longer signs anyone in
        Exchange meAfterSignOut = get(client, appUrl("/me"), signIn.sessionCookie());
        assertThat(meAfterSignOut.status()).isEqualTo(401);
        assertThat(meAfterSignOut.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(meAfterSignOut.body(), "$.status")).intValue()).isEqualTo(401);
    }
}
