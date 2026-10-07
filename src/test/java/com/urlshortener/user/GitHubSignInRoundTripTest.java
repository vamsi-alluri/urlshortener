package com.urlshortener.user;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GitHub OAuth sign-in round trip over the real HTTP boundary (issue #2): the sign-in page
 * initiates the redirect to GitHub, the callback creates the User and issues a session cookie,
 * repeat sign-ins reuse the same User row, and {@code GET /me} answers with the GitHub identity
 * or 401 problem+json.
 */
class GitHubSignInRoundTripTest extends AbstractGitHubSignInHttpTest {

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

    @Autowired
    private UserRepository users;

    @Test
    void aVisitorIsRedirectedToGitHubByTheSignInLink() throws Exception {
        HttpClient client = noRedirects();

        Exchange loginPage = get(client, appUrl("/login"));
        assertThat(loginPage.status()).as("the sign-in page renders").isEqualTo(200);
        assertThat(loginPage.body()).as("the only sign-in path is the GitHub link (ADR-0003)")
                .contains("Sign in with GitHub")
                .contains("/oauth2/authorization/github");

        Exchange initiation = get(client, appUrl("/oauth2/authorization/github"));
        assertThat(initiation.status()).as("the initiation endpoint redirects to GitHub").isEqualTo(302);
        String authorizeUrl = initiation.location();
        assertThat(authorizeUrl).startsWith(GIT_HUB.url("/oauth/authorize").toString());
        Map<String, String> query = queryOf(authorizeUrl);
        assertThat(query.get("client_id")).isEqualTo(CLIENT_ID);
        assertThat(query.get("state")).as("the state protects the flow against CSRF").isNotBlank();
        assertThat(query.get("redirect_uri")).endsWith("/login/oauth2/code/github");
    }

    @Test
    void theCallbackSignsTheVisitorInAndCreatesTheUser() throws Exception {
        String octocat = "{\"id\":12345,\"login\":\"octocat\",\"name\":\"Octo Cat\",\"email\":\"octocat@example.com\"}";
        SignInResult signIn = signInViaGitHub(GIT_HUB, octocat);

        // one round trip: the callback lands already signed in, on the page — no local form
        assertThat(signIn.landingLocation()).endsWith("/login");

        // the issued session cookie proves the GitHub identity at /me
        HttpClient client = noRedirects();
        Exchange me = get(client, appUrl("/me"), signIn.sessionCookie());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.header("Content-Type")).contains("application/json");
        assertThat((String) JsonPath.read(me.body(), "$.login")).isEqualTo("octocat");
        assertThat(((Number) JsonPath.read(me.body(), "$.githubId")).longValue()).isEqualTo(12345);

        // the User row exists — GitHub identity, profile, created_at, and no password anywhere
        User user = users.findByGithubId(12345).orElseThrow();
        assertThat(user.getId()).isNotNull();
        assertThat(user.getLogin()).isEqualTo("octocat");
        assertThat(user.getName()).isEqualTo("Octo Cat");
        assertThat(user.getEmail()).isEqualTo("octocat@example.com");
        assertThat(user.getCreatedAt()).isNotNull();
    }

    @Test
    void repeatSignInReusesTheSameUserRow() throws Exception {
        String before = "{\"id\":54321,\"login\":\"mona\",\"name\":\"Mona Lisa\",\"email\":\"mona@example.com\"}";
        String renamed = "{\"id\":54321,\"login\":\"mona\",\"name\":\"Mona Lisa Octocat\",\"email\":\"mona@elsewhere.example\"}";

        SignInResult firstSignIn = signInViaGitHub(GIT_HUB, before);
        SignInResult secondSignIn = signInViaGitHub(GIT_HUB, renamed);

        HttpClient client = noRedirects();
        Exchange meAfterFirst = get(client, appUrl("/me"), firstSignIn.sessionCookie());
        assertThat(meAfterFirst.status()).isEqualTo(200);
        long userId = ((Number) JsonPath.read(meAfterFirst.body(), "$.id")).longValue();

        Exchange meAfterSecond = get(client, appUrl("/me"), secondSignIn.sessionCookie());
        assertThat(meAfterSecond.status()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(meAfterSecond.body(), "$.id")).longValue())
                .as("repeat sign-in reuses the same account, so the links stay mine")
                .isEqualTo(userId);

        assertThat(users.countByGithubId(54321)).as("exactly one row per GitHub identity").isEqualTo(1);
        User user = users.findByGithubId(54321).orElseThrow();
        assertThat(user.getId()).isEqualTo(userId);
        assertThat(user.getName()).as("the profile refreshes from GitHub").isEqualTo("Mona Lisa Octocat");
        assertThat(user.getEmail()).isEqualTo("mona@elsewhere.example");
    }

    @Test
    void meWithoutASessionIsUnauthorizedProblemJson() throws Exception {
        Exchange me = get(noRedirects(), appUrl("/me"));
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(me.body(), "$.status")).intValue()).isEqualTo(401);
        assertThat((String) JsonPath.read(me.body(), "$.title")).isEqualTo("Unauthorized");
    }

    @Test
    void aCallbackThatCannotBeValidatedSignsNobodyIn() throws Exception {
        mockGitHubUser(GIT_HUB,
                "{\"id\":98765,\"login\":\"intruder\",\"name\":\"Intruder\",\"email\":\"intruder@example.com\"}");
        HttpClient client = noRedirects();

        Exchange initiation = get(client, appUrl("/oauth2/authorization/github"));
        Exchange authorized = get(client, initiation.location());

        // the callback arrives without the session holding the authorization request, so the
        // state cannot be validated — Spring Security must refuse the sign-in
        Exchange callback = get(client, resolve(authorized.location()));
        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.location()).endsWith("/login?error");

        assertThat(users.countByGithubId(98765)).as("no User row is created for a refused callback").isZero();
    }
}
