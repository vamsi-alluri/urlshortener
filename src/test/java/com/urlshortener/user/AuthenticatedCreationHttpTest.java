package com.urlshortener.user;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authenticated creation and Destination validation over the real HTTP boundary (issue #4,
 * the plan on the issue): creating a Short Link requires a valid Bearer key — no credential
 * is a 401 problem+json, never a login-page redirect — a signed-in User's key creates and
 * attributes the link to them, regeneration revokes creation for the old key, and a
 * Destination must be a public web URL: non-web schemes, localhost, private IP literals, and
 * oversize values each answer 400 problem+json with their type code, all violations together.
 *
 * <p>Everything rides the one HTTP seam: the application on a random port, sessions in a real
 * SQLite database, GitHub mocked at its HTTP boundary (the #2/#3 base class's dance). The one
 * permitted non-HTTP assertion is the storage check of plan item 3 — the stored link's owner.
 */
class AuthenticatedCreationHttpTest extends AbstractGitHubSignInHttpTest {

    private static final Path DATABASE_FILE = newSqliteFile();
    private static final MockWebServer GIT_HUB = startMockGitHub();

    /** D7: ush_ + 32 base62 characters — the one shape an issued key has. */
    private static final Pattern KEY_SHAPE = Pattern.compile("ush_[A-Za-z0-9]{32}");

    /** D2: the Slug is 7 base62 characters. */
    private static final Pattern SLUG_SHAPE = Pattern.compile("[A-Za-z0-9]{7}");

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registerTestProperties(registry, DATABASE_FILE, GIT_HUB);
    }

    @AfterAll
    static void shutDown() {
        tearDown(GIT_HUB, DATABASE_FILE);
    }

    /** Reaches behind the HTTP seam for the one permitted non-HTTP assertion (plan item 3). */
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creationWithoutAnyCredentialIs401ProblemJsonNotARedirect() throws Exception {
        // no Authorization header, no session: an API caller gets 401 problem+json — the 401
        // entry point covers /api/**, not just /me, so never a login-page redirect
        Exchange creation = postJson(noRedirects(), appUrl("/api/links"),
                "{\"destination\":\"https://example.com/no-credential\"}");
        assertThat(creation.status()).isEqualTo(401);
        assertThat(creation.location()).as("an API path never redirects to the sign-in page").isNull();
        assertThat(creation.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(creation.body(), "$.status")).intValue()).isEqualTo(401);
        assertThat((String) JsonPath.read(creation.body(), "$.title")).isEqualTo("Unauthorized");
    }

    @Test
    void aUserCreatesAShortLinkWithTheirBearerKey() throws Exception {
        String key = bearerKeyFor(4102, "creator");

        // the key alone is the entire credential: no session cookie anywhere
        Exchange creation = createWithBearer(key, "https://example.com/created-with-my-key");
        assertThat(creation.status()).as("the key alone is enough to create").isEqualTo(201);
        assertThat(creation.header("Content-Type")).contains("application/json");
        String slug = (String) JsonPath.read(creation.body(), "$.slug");
        assertThat(slug).matches(SLUG_SHAPE);
        assertThat((String) JsonPath.read(creation.body(), "$.short_url")).endsWith("/" + slug);
        assertThat((String) JsonPath.read(creation.body(), "$.destination"))
                .isEqualTo("https://example.com/created-with-my-key");
    }

    @Test
    void creationRecordsTheKeyholderAsTheOwner() throws Exception {
        String key = bearerKeyFor(4103, "owner-user");
        long keyholderId = userIdOf(getWithBearer(noRedirects(), appUrl("/me"), key));

        String slug = (String) JsonPath.read(
                createWithBearer(key, "https://example.com/owned-by-me").body(), "$.slug");

        // the one permitted non-HTTP assertion (plan item 3): the owner the row records —
        // the V1 column that has waited nullable since #1 is filled with the key's users.id
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT destination, owner FROM links WHERE slug = ?", slug);
        assertThat(row.get("owner"))
                .as("the link is attributed to the keyholder's users.id")
                .isEqualTo(String.valueOf(keyholderId));
        assertThat(row.get("destination")).isEqualTo("https://example.com/owned-by-me");
    }

    @Test
    void regeneratingTheKeyRevokesCreationForTheOldKey() throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(4104, "rotating-creator"));
        HttpClient client = noRedirects();
        Exchange keyPage = get(client, appUrl("/me/key"), signIn.sessionCookie());
        String firstKey = theIssuedKey(keyPage.body());
        assertThat(createWithBearer(firstKey, "https://example.com/before-rotation").status())
                .as("the key creates before it is rotated")
                .isEqualTo(201);

        // the regenerate action: a session- and CSRF-authenticated form POST from the key page
        Exchange regenerated = postForm(client, appUrl("/me/key"),
                Map.of("_csrf", csrfTokenFrom(keyPage.body())), signIn.sessionCookie());
        assertThat(regenerated.status()).as("the regenerate action renders the new key").isEqualTo(200);
        String secondKey = theIssuedKey(regenerated.body());

        // the old key no longer creates; the new one does, immediately
        assertUnauthorizedProblemJson(
                createWithBearer(firstKey, "https://example.com/after-rotation"));
        assertThat(createWithBearer(secondKey, "https://example.com/after-rotation").status())
                .as("the new key creates immediately")
                .isEqualTo(201);
    }

    @Test
    void nonWebSchemesAreRejectedAsInvalidScheme() throws Exception {
        String key = bearerKeyFor(4105, "scheme-checker");
        for (String destination : new String[] {
                "javascript:alert('shortener')",   // the weird-URI launcher (story 4)
                "data:text/plain;base64,SGVsbG8=",
                "ftp://example.com/file",
                "mailto:someone@example.com"}) {
            assertRejectedWithTypeCodes(createWithBearer(key, destination), destination,
                    "invalid_scheme");
        }
    }

    @Test
    void localhostDestinationsAreRejectedAsPrivate() throws Exception {
        String key = bearerKeyFor(4106, "host-checker");
        for (String destination : new String[] {
                "http://localhost/x",
                "http://sub.localhost/x",
                "http://LOCALHOST/x",       // DNS names are case-insensitive
                "http://localhost./x"}) {   // the trailing dot still means localhost
            assertRejectedWithTypeCodes(createWithBearer(key, destination), destination,
                    "private_destination");
        }
    }

    @Test
    void privateIpLiteralsAreRejectedAsPrivate() throws Exception {
        String key = bearerKeyFor(4107, "address-checker");
        for (String destination : new String[] {
                "http://127.0.0.1/x",           // loopback 127/8
                "http://10.0.0.1/x",            // private 10/8
                "http://172.16.0.1/x",          // private 172.16/12, first address
                "http://172.31.255.254/x",      // private 172.16/12, last address
                "http://192.168.1.1/x",         // private 192.168/16
                "http://169.254.169.254/x",     // link-local 169.254/16
                "http://[::1]/x",               // IPv6 loopback
                "http://[fe80::1]/x",           // IPv6 link-local
                "http://[fc00::1]/x",           // IPv6 unique-local
                "http://[0:0:0:0:0:0:0:1]/x",   // the loopback written in full
                "http://[::ffff:10.0.0.1]/x"}) { // a private IPv4, mapped into IPv6
            assertRejectedWithTypeCodes(createWithBearer(key, destination), destination,
                    "private_destination");
        }
    }

    @Test
    void oversizeDestinationsAreRejectedWhileThe2048BoundaryIsAccepted() throws Exception {
        String key = bearerKeyFor(4108, "length-checker");
        String prefix = "https://example.com/";
        String atTheLimit = prefix + "a".repeat(2048 - prefix.length());
        assertThat(atTheLimit.length()).as("the boundary case is exactly 2048 characters")
                .isEqualTo(2048);

        Exchange boundary = createWithBearer(key, atTheLimit);
        assertThat(boundary.status()).as("exactly 2048 characters is accepted").isEqualTo(201);
        assertThat((String) JsonPath.read(boundary.body(), "$.destination")).isEqualTo(atTheLimit);

        String overTheLimit = prefix + "a".repeat(2049 - prefix.length());
        assertRejectedWithTypeCodes(createWithBearer(key, overTheLimit), "a 2049-character value",
                "destination_too_long");
    }

    @Test
    void aDestinationBreakingTwoRulesReportsBothTypeCodesTogether() throws Exception {
        String key = bearerKeyFor(4109, "both-rules");

        // a wrong scheme pointing into a private network
        assertRejectedWithTypeCodes(createWithBearer(key, "ftp://192.168.1.1/file"),
                "ftp://192.168.1.1/file", "invalid_scheme", "private_destination");

        // localhost, far too long
        String prefix = "http://localhost/";
        assertRejectedWithTypeCodes(
                createWithBearer(key, prefix + "a".repeat(2049 - prefix.length())),
                "localhost over the limit", "private_destination", "destination_too_long");
    }

    @Test
    void followingAShortLinkStaysPublicWithoutAnyCredential() throws Exception {
        String key = bearerKeyFor(4110, "open-follow");
        String destination = "https://example.com/follow-me-freely";
        String slug = (String) JsonPath.read(createWithBearer(key, destination).body(), "$.slug");

        // a Visitor with no session and no key follows Short Links exactly as before (#1)
        Exchange follow = get(noRedirects(), appUrl("/" + slug));
        assertThat(follow.status()).isEqualTo(302);
        assertThat(follow.location()).isEqualTo(destination);
    }

    @Test
    void shortLinksThatPredateTheRulesStillResolve() throws Exception {
        // a row from before #4: a Destination the new rules would reject, no owner —
        // validation gates creation only; existing Short Links are untouched (story 7)
        jdbc.update("""
                INSERT INTO links (slug, destination, owner, click_count, created_at, deactivated_at)
                VALUES ('legacy0', 'http://192.168.1.1/created-before-the-rules', NULL, 0,
                        '2026-10-01T00:00:00Z', NULL)
                """);

        Exchange follow = get(noRedirects(), appUrl("/legacy0"));
        assertThat(follow.status()).as("the 302 the Visitor always got").isEqualTo(302);
        assertThat(follow.location()).isEqualTo("http://192.168.1.1/created-before-the-rules");
    }

    @Test
    void publicIpLiteralsAreAccepted() throws Exception {
        String key = bearerKeyFor(4111, "public-checker");
        for (String destination : new String[] {
                "http://172.32.0.1/x",        // just outside 172.16/12
                "http://172.15.255.255/x",    // just below it
                "http://8.8.8.8/x",           // a public IPv4
                "http://[2001:db8::1]/x",     // a public IPv6
                "http://[::ffff:8.8.8.8]/x"}) { // a mapped public IPv4
            assertThat(createWithBearer(key, destination).status())
                    .as("a public Destination is accepted: %s", destination)
                    .isEqualTo(201);
        }
    }

    /** One full sign-in dance for a fresh User, returning their first-issued key (D7). */
    private String bearerKeyFor(long githubId, String login) throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(githubId, login));
        Exchange keyPage = get(noRedirects(), appUrl("/me/key"), signIn.sessionCookie());
        assertThat(keyPage.status()).as("the key page issues the key on first visit").isEqualTo(200);
        return theIssuedKey(keyPage.body());
    }

    /** POST /api/links with the Bearer key as the only credential — the creation seam (#4). */
    private Exchange createWithBearer(String key, String destination) throws Exception {
        return postJsonWithBearer(noRedirects(), appUrl("/api/links"),
                "{\"destination\":\"" + destination + "\"}", key);
    }

    private static Exchange postJsonWithBearer(HttpClient client, String url, String json,
            String bearerKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Exchange(response.statusCode(), response.headers().map(), response.body());
    }

    /** The one key shown on a page response that issued it. */
    private static String theIssuedKey(String pageBody) {
        Matcher issued = KEY_SHAPE.matcher(pageBody);
        if (!issued.find()) {
            throw new IllegalStateException("no issued key found on the key page: " + pageBody);
        }
        return issued.group();
    }

    private static String githubProfile(long githubId, String login) {
        return "{\"id\":" + githubId + ",\"login\":\"" + login + "\",\"name\":\"" + login
                + " User\",\"email\":\"" + login + "@example.com\"}";
    }

    private static long userIdOf(Exchange me) {
        return ((Number) JsonPath.read(me.body(), "$.id")).longValue();
    }

    /** The 400 problem+json of a rejected Destination, reporting exactly the given type codes. */
    private static void assertRejectedWithTypeCodes(Exchange creation, String destination,
            String... expectedTypeCodes) {
        assertThat(creation.status()).as("rejected: %s", destination).isEqualTo(400);
        assertThat(creation.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(creation.body(), "$.status")).intValue()).isEqualTo(400);
        assertThat((String) JsonPath.read(creation.body(), "$.title")).isEqualTo("Bad Request");
        List<String> reportedTypeCodes = JsonPath.read(creation.body(), "$.violations[*].type");
        assertThat(reportedTypeCodes)
                .as("every broken rule reported together for: %s", destination)
                .containsExactlyInAnyOrder(expectedTypeCodes);
    }

    private static void assertUnauthorizedProblemJson(Exchange exchange) {
        assertThat(exchange.status()).isEqualTo(401);
        assertThat(exchange.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(exchange.body(), "$.status")).intValue()).isEqualTo(401);
        assertThat((String) JsonPath.read(exchange.body(), "$.title")).isEqualTo("Unauthorized");
    }
}
