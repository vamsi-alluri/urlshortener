package com.urlshortener.user;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single API Key per User over the real HTTP boundary (issue #3, the plan on the issue):
 * the key page issues the key once in the documented shape, the Bearer key authenticates
 * {@code GET /me} with no session at all, malformed and unknown keys answer 401 problem+json,
 * regeneration revokes the previous key instantly, each User's key shows only their own
 * identity — and the plaintext key is never stored.
 *
 * <p>The one permitted non-HTTP assertion is the storage check (plan item 6): the row behind
 * a key holds the SHA-256 hash, not the plaintext.
 */
class ApiKeyHttpTest extends AbstractGitHubSignInHttpTest {

    private static final Path DATABASE_FILE = newSqliteFile();
    private static final MockWebServer GIT_HUB = startMockGitHub();

    /** D7: {@code ush_} + 32 base62 characters. */
    private static final Pattern KEY_SHAPE = Pattern.compile("ush_[A-Za-z0-9]{32}");

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registerTestProperties(registry, DATABASE_FILE, GIT_HUB);
    }

    @AfterAll
    static void shutDown() {
        tearDown(GIT_HUB, DATABASE_FILE);
    }

    /** The one permitted non-HTTP seam (plan item 6): the stored row behind a key. */
    @Autowired
    private ApiKeyRepository apiKeys;

    @Test
    void theKeyPageIssuesTheKeyOnceInTheDocumentedShape() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB,
                "{\"id\":3001,\"login\":\"keyholder\",\"name\":\"Key Holder\",\"email\":\"keyholder@example.com\"}");
        HttpClient client = noRedirects();

        Exchange keyPage = get(client, appUrl("/me/key"), signIn.sessionCookie());
        assertThat(keyPage.status()).as("the key page renders for the signed-in User").isEqualTo(200);
        String key = theIssuedKey(keyPage.body());
        assertThat(key).as("D7: ush_ + 32 base62 characters").matches(KEY_SHAPE);
        assertThat(keyPage.body()).as("the page says the key is shown once").contains("Shown only once");

        // shown exactly once: a later visit cannot display it again — only its hash is stored
        Exchange revisit = get(client, appUrl("/me/key"), signIn.sessionCookie());
        assertThat(revisit.status()).isEqualTo(200);
        assertThat(revisit.body()).as("the plaintext key is never shown twice").doesNotContain(key);
        assertThat(revisit.body()).contains("stored hashed");
    }

    @Test
    void aBearerKeyAuthenticatesMeWithNoSessionAtAll() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB,
                "{\"id\":3002,\"login\":\"bearer-user\",\"name\":\"Bearer User\",\"email\":\"bearer@example.com\"}");
        String key = theIssuedKey(get(noRedirects(), appUrl("/me/key"), signIn.sessionCookie()).body());

        // no Cookie header at all: the Bearer key is the entire credential
        Exchange me = getWithBearer(noRedirects(), appUrl("/me"), key);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.header("Content-Type")).contains("application/json");
        assertThat((String) JsonPath.read(me.body(), "$.login")).isEqualTo("bearer-user");
        assertThat(((Number) JsonPath.read(me.body(), "$.githubId")).longValue()).isEqualTo(3002);
        assertThat(((Number) JsonPath.read(me.body(), "$.id")).longValue())
                .as("the keyholder's users.id, the stable owner identity")
                .isPositive();

        assertThat(me.header("Set-Cookie")).as("no session is created on the Bearer path").isNull();
    }

    @Test
    void malformedOrUnknownKeysAreRejectedAsUnauthorizedProblemJson() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB,
                "{\"id\":3003,\"login\":\"strict-user\",\"name\":\"Strict User\",\"email\":\"strict@example.com\"}");
        String issuedKey = theIssuedKey(get(noRedirects(), appUrl("/me/key"), signIn.sessionCookie()).body());

        // malformed: not the documented shape
        assertUnauthorizedProblemJson(getWithBearer(noRedirects(), appUrl("/me"), "not-a-key-shape"));
        // unknown: well-formed (D7), but no row ever held it
        assertUnauthorizedProblemJson(getWithBearer(noRedirects(), appUrl("/me"), neverIssuedKeyFrom(issuedKey)));
    }

    @Test
    void regeneratingRevokesThePreviousKeyInstantly() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB,
                "{\"id\":3004,\"login\":\"rotate-user\",\"name\":\"Rotate User\",\"email\":\"rotate@example.com\"}");
        HttpClient client = noRedirects();

        Exchange keyPage = get(client, appUrl("/me/key"), signIn.sessionCookie());
        String firstKey = theIssuedKey(keyPage.body());

        // the regenerate action: a session- and CSRF-authenticated form POST from the key page
        Exchange regenerated = postForm(client, appUrl("/me/key"), Map.of("_csrf", csrfTokenFrom(keyPage.body())),
                signIn.sessionCookie());
        assertThat(regenerated.status()).as("the regenerate action renders the new key").isEqualTo(200);
        assertThat(regenerated.body()).contains("revoked");
        String secondKey = theIssuedKey(regenerated.body());
        assertThat(secondKey).matches(KEY_SHAPE);
        assertThat(secondKey).isNotEqualTo(firstKey);

        // the previous key stops working the instant its row was replaced
        assertUnauthorizedProblemJson(getWithBearer(noRedirects(), appUrl("/me"), firstKey));
        Exchange meWithNewKey = getWithBearer(noRedirects(), appUrl("/me"), secondKey);
        assertThat(meWithNewKey.status()).as("the new key authenticates immediately").isEqualTo(200);
        assertThat((String) JsonPath.read(meWithNewKey.body(), "$.login")).isEqualTo("rotate-user");
    }

    @Test
    void eachUsersKeyShowsOnlyTheirOwnIdentity() throws Exception {
        SignInResult aliceSignIn = signInViaGitHub(GIT_HUB,
                "{\"id\":3101,\"login\":\"alice\",\"name\":\"Alice\",\"email\":\"alice@example.com\"}");
        String aliceKey = theIssuedKey(get(noRedirects(), appUrl("/me/key"), aliceSignIn.sessionCookie()).body());
        long aliceUserId = userIdOf(get(noRedirects(), appUrl("/me"), aliceSignIn.sessionCookie()));

        SignInResult bobSignIn = signInViaGitHub(GIT_HUB,
                "{\"id\":3201,\"login\":\"bob\",\"name\":\"Bob\",\"email\":\"bob@example.com\"}");
        String bobKey = theIssuedKey(get(noRedirects(), appUrl("/me/key"), bobSignIn.sessionCookie()).body());
        long bobUserId = userIdOf(get(noRedirects(), appUrl("/me"), bobSignIn.sessionCookie()));

        assertThat(aliceKey).isNotEqualTo(bobKey);
        assertThat(aliceUserId).isNotEqualTo(bobUserId);

        Exchange meWithAlicesKey = getWithBearer(noRedirects(), appUrl("/me"), aliceKey);
        assertThat((String) JsonPath.read(meWithAlicesKey.body(), "$.login")).isEqualTo("alice");
        assertThat(userIdOf(meWithAlicesKey)).isEqualTo(aliceUserId);

        Exchange meWithBobsKey = getWithBearer(noRedirects(), appUrl("/me"), bobKey);
        assertThat((String) JsonPath.read(meWithBobsKey.body(), "$.login")).isEqualTo("bob");
        assertThat(userIdOf(meWithBobsKey)).isEqualTo(bobUserId);
    }

    @Test
    void theStoredValueIsTheHashNotThePlaintextKey() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB,
                "{\"id\":3301,\"login\":\"hashed-user\",\"name\":\"Hashed User\",\"email\":\"hashed@example.com\"}");
        HttpClient client = noRedirects();
        String key = theIssuedKey(get(client, appUrl("/me/key"), signIn.sessionCookie()).body());
        long userId = userIdOf(get(client, appUrl("/me"), signIn.sessionCookie()));

        // the one non-HTTP assertion in the plan: what the row actually stores (D8)
        String stored = apiKeys.findByUserId(userId).orElseThrow().getKeyHash();
        assertThat(stored).as("the plaintext key is never stored").isNotEqualTo(key);
        assertThat(stored).as("the stored value is the key's SHA-256 hex").isEqualTo(ApiKey.hashOf(key));
    }

    @Test
    void unauthenticatedLinkCreationStillWorks() throws Exception {
        // regression guard until #4: POST /api/links stays open with no session and no key
        Exchange created = postJson(noRedirects(), appUrl("/api/links"),
                "{\"destination\":\"https://example.com/with-no-credential\"}");
        assertThat(created.status()).as("creation stays unauthenticated until #4").isEqualTo(201);
        String slug = (String) JsonPath.read(created.body(), "$.slug");
        assertThat(slug).matches("[A-Za-z0-9]{7}");
        assertThat((String) JsonPath.read(created.body(), "$.short_url")).endsWith("/" + slug);
    }

    @Test
    void aVisitorIsRedirectedToTheSignInPageFromTheKeyPage() throws Exception {
        // the key page is session-authenticated like /me, but it is a page for browsers: a
        // Visitor is sent to the sign-in page, not answered with 401 problem+json
        Exchange keyPage = get(noRedirects(), appUrl("/me/key"));
        assertThat(keyPage.status()).isEqualTo(302);
        assertThat(keyPage.location()).endsWith("/login");
    }

    /** The one key shown on a page response that issued it. */
    private static String theIssuedKey(String pageBody) {
        Matcher issued = KEY_SHAPE.matcher(pageBody);
        if (!issued.find()) {
            throw new IllegalStateException("no issued key found on the key page: " + pageBody);
        }
        return issued.group();
    }

    /** A well-formed key that was never issued: the issued key with its last character changed. */
    private static String neverIssuedKeyFrom(String issuedKey) {
        char last = issuedKey.charAt(issuedKey.length() - 1);
        char different = last == '0' ? '1' : '0';
        return issuedKey.substring(0, issuedKey.length() - 1) + different;
    }

    private static long userIdOf(Exchange me) {
        return ((Number) JsonPath.read(me.body(), "$.id")).longValue();
    }

    private static void assertUnauthorizedProblemJson(Exchange exchange) {
        assertThat(exchange.status()).isEqualTo(401);
        assertThat(exchange.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(exchange.body(), "$.status")).intValue()).isEqualTo(401);
        assertThat((String) JsonPath.read(exchange.body(), "$.title")).isEqualTo("Unauthorized");
    }
}
