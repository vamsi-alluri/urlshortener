package com.urlshortener.user;

import java.nio.file.Path;

import com.jayway.jsonpath.JsonPath;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session survives a restart of the service (issue #2, user story 3): Spring Session persists
 * the session in the same SQLite database, so signing in, destroying the application context,
 * and booting a brand-new one (a redeploy) against the same database leaves the session — and
 * the signed-in identity — intact.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionSurvivesRestartTest extends AbstractGitHubSignInHttpTest {

    private static final Path DATABASE_FILE = newSqliteFile();
    private static final MockWebServer GIT_HUB = startMockGitHub();

    /** Carried across the restart boundary (a different application context, same JVM). */
    private static volatile String sessionCookie;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registerTestProperties(registry, DATABASE_FILE, GIT_HUB);
    }

    @AfterAll
    static void shutDown() {
        tearDown(GIT_HUB, DATABASE_FILE);
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void signInBeforeTheRestart() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB,
                "{\"id\":1001,\"login\":\"restart-user\",\"name\":\"Restart User\",\"email\":\"restart@example.com\"}");
        assertThat(signIn.sessionCookie()).as("the callback issues a session cookie").isNotNull();
        sessionCookie = signIn.sessionCookie();
    }

    @Test
    @Order(2)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void theSessionStillSignsMeInAfterTheRestart() throws Exception {
        // a brand-new application context (the redeploy) points at the same SQLite database
        Exchange me = get(noRedirects(), appUrl("/me"), sessionCookie);
        assertThat(me.status()).as("the session persisted in SQLite survives the restart").isEqualTo(200);
        assertThat((String) JsonPath.read(me.body(), "$.login")).isEqualTo("restart-user");
        assertThat(((Number) JsonPath.read(me.body(), "$.githubId")).longValue()).isEqualTo(1001);
    }
}
