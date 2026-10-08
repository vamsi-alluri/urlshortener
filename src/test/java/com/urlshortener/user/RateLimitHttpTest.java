package com.urlshortener.user;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-key creation throttle over the real HTTP boundary (issue #7, the plan on the
 * issue): with the allowance configured low, exactly N creations succeed and the N+1th
 * answers 429 problem+json with a {@code Retry-After} header; a second User's
 * allowance is untouched while the first is throttled; the redirect, Bearer reads, and
 * everything else on {@code /api/**} are never limited; and garbage keys fail closed
 * inside the Bearer filter — before the throttle — consuming no allowance, so a valid
 * key afterwards still gets its full one.
 *
 * <p>Everything rides the one HTTP seam (G3): the application on a random port,
 * sessions in a real SQLite database, GitHub mocked at its HTTP boundary (the #2/#3
 * base class's dance), and the allowance set low through
 * {@code app.rate-limit.creations-per-hour} — the same knob that keeps the
 * default-60 suites of the plan's item 8 safe.
 */
class RateLimitHttpTest extends AbstractGitHubSignInHttpTest {

    /** The allowance for this run's keys: low, so the boundary is a few requests away (D15). */
    private static final int CREATIONS_PER_HOUR = 3;

    private static final Path DATABASE_FILE = newSqliteFile();

    private static final MockWebServer GIT_HUB = startMockGitHub();

    /** D7: ush_ + 32 base62 characters — the one shape an issued key has. */
    private static final Pattern KEY_SHAPE = Pattern.compile("ush_[A-Za-z0-9]{32}");

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registerTestProperties(registry, DATABASE_FILE, GIT_HUB);
        registry.add("app.rate-limit.creations-per-hour", () -> String.valueOf(CREATIONS_PER_HOUR));
    }

    @AfterAll
    static void shutDown() {
        tearDown(GIT_HUB, DATABASE_FILE);
    }

    /** Plan item 1: exactly at the limit — the Nth creation still succeeds. */
    @Test
    void exactlyAtTheLimitTheNthCreationStillSucceeds() throws Exception {
        String key = bearerKeyFor(4501, "at-the-limit");

        for (int creation = 1; creation <= CREATIONS_PER_HOUR; creation++) {
            Exchange created = createWithBearer(key, destination(4501, creation));
            assertThat(created.status())
                    .as("creation %d of the %d-per-hour allowance succeeds — the Nth included",
                            creation, CREATIONS_PER_HOUR)
                    .isEqualTo(201);
        }
    }

    /** Plan item 2: beyond the limit — the N+1th creation is 429 problem+json with Retry-After. */
    @Test
    void beyondTheLimitTheNextCreationIs429ProblemJsonWithRetryAfter() throws Exception {
        String key = bearerKeyFor(4502, "beyond-the-limit");

        assertTooManyRequestsProblemJson(throttle(key, 4502));
    }

    /**
     * Plan item 3: per-key isolation — a second User's creations are untouched while the
     * first is throttled.
     */
    @Test
    void aSecondUsersCreationsAreUntouchedWhileTheFirstIsThrottled() throws Exception {
        String abuserKey = bearerKeyFor(4503, "abuser");
        assertThat(throttle(abuserKey, 4503).status())
                .as("the first User is throttled past their allowance")
                .isEqualTo(429);

        String neighbourKey = bearerKeyFor(4504, "neighbour");
        for (int creation = 1; creation <= CREATIONS_PER_HOUR; creation++) {
            assertThat(createWithBearer(neighbourKey, destination(4504, creation)).status())
                    .as("the second User's creation %d rides their own untouched allowance", creation)
                    .isEqualTo(201);
        }
        assertThat(createWithBearer(neighbourKey, destination(4504, CREATIONS_PER_HOUR + 1)).status())
                .as("the second User meets their own limit eventually — never the first User's")
                .isEqualTo(429);
    }

    /**
     * Plan item 4: the redirect is never limited — {@code GET /{slug}} far beyond the
     * limit keeps 302ing.
     */
    @Test
    void theRedirectIsNeverLimited() throws Exception {
        String key = bearerKeyFor(4505, "redirect-hammer");
        String destination = "https://example.com/followed-far-beyond-the-limit";
        String slug = (String) JsonPath.read(createWithBearer(key, destination).body(), "$.slug");
        assertThat(throttle(key, 4505).status())
                .as("the key is spent — every follow below happens beyond the limit")
                .isEqualTo(429);

        // far beyond the limit, with no credential at all: the public follow path has no limiter
        HttpClient client = noRedirects();
        for (int follow = 1; follow <= 2 * CREATIONS_PER_HOUR + 10; follow++) {
            Exchange followed = get(client, appUrl("/" + slug));
            assertThat(followed.status()).as("follow %d: the redirect is never limited", follow).isEqualTo(302);
            assertThat(followed.location()).isEqualTo(destination);
        }
    }

    /**
     * Plan item 5: Bearer reads are never limited — {@code GET /me} with the key far
     * beyond the limit keeps 200ing.
     */
    @Test
    void bearerReadsAreNeverLimited() throws Exception {
        String key = bearerKeyFor(4506, "reader");
        assertThat(throttle(key, 4506).status())
                .as("the key's creation allowance is spent")
                .isEqualTo(429);

        for (int read = 1; read <= 2 * CREATIONS_PER_HOUR + 10; read++) {
            Exchange me = getWithBearer(noRedirects(), appUrl("/me"), key);
            assertThat(me.status()).as("read %d: GET /me is never limited", read).isEqualTo(200);
            assertThat(((Number) JsonPath.read(me.body(), "$.id")).longValue())
                    .as("read %d still answers the keyholder's identity", read)
                    .isPositive();
        }
    }

    /**
     * Plan item 6: garbage keys fail closed before the limiter — hammering with bad keys
     * yields 401s (the #3 Bearer filter, pre-controller) and consumes no allowance: a
     * valid key afterwards still gets its full one.
     */
    @Test
    void garbageKeysFailClosedBeforeTheLimiterAndConsumeNoAllowance() throws Exception {
        for (int attempt = 1; attempt <= CREATIONS_PER_HOUR + 5; attempt++) {
            // alternating garbage: well-formed but never issued (D7 shape, no row), then malformed outright
            String garbageKey = attempt % 2 == 1 ? "ush_" + "0".repeat(32) : "not-a-key-shape";
            assertUnauthorizedProblemJson(createWithBearer(garbageKey, "https://example.com/never-reached"));
        }

        String key = bearerKeyFor(4507, "after-garbage");
        for (int creation = 1; creation <= CREATIONS_PER_HOUR; creation++) {
            assertThat(createWithBearer(key, destination(4507, creation)).status())
                    .as("creation %d of the full allowance — the garbage hammering consumed nothing", creation)
                    .isEqualTo(201);
        }
    }

    /**
     * Plan item 7: scope is creation only — nothing else on {@code /api/**} trips the
     * limiter: {@code GET /api/links} — #5's list/read route when it lands — never
     * 429s (it answers 405 today: the route exists for creation only), and it
     * consumes no allowance either.
     */
    @Test
    void nothingElseOnTheApiTripsTheCreationLimiter() throws Exception {
        String key = bearerKeyFor(4508, "read-only");

        for (int read = 1; read <= CREATIONS_PER_HOUR + 5; read++) {
            Exchange listed = getWithBearer(noRedirects(), appUrl("/api/links"), key);
            assertThat(listed.status())
                    .as("read %d: nothing but creation is limited — 405 today, and #5's list route"
                            + " must keep this true: never 429", read)
                    .isNotEqualTo(429);
        }

        // the reads consumed no allowance: the key still holds its full N creations
        for (int creation = 1; creation <= CREATIONS_PER_HOUR; creation++) {
            assertThat(createWithBearer(key, destination(4508, creation)).status())
                    .as("creation %d — the /api reads above consumed nothing", creation)
                    .isEqualTo(201);
        }
        assertThat(createWithBearer(key, destination(4508, CREATIONS_PER_HOUR + 1)).status())
                .as("the throttle still counts exactly creations — no more than the configured N")
                .isEqualTo(429);
    }

    /**
     * Creates with the key until it is throttled, asserting every pre-throttle creation
     * is a 201 — and returns the 429 exchange. The key's allowance is spent afterwards.
     */
    private Exchange throttle(String key, long githubId) throws Exception {
        for (int creation = 1; creation <= CREATIONS_PER_HOUR + 1; creation++) {
            Exchange created = createWithBearer(key, destination(githubId, creation));
            if (created.status() == 429) {
                return created;
            }
            assertThat(created.status())
                    .as("creation %d before the throttle answers 201, not %s", creation, created.body())
                    .isEqualTo(201);
        }
        throw new IllegalStateException("the key was never throttled — the configured limit is not in force");
    }

    /** The 429 problem+json of a throttled creation, with the Retry-After signal (plan item 2). */
    private static void assertTooManyRequestsProblemJson(Exchange throttled) {
        assertThat(throttled.status()).isEqualTo(429);
        assertThat(throttled.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(throttled.body(), "$.status")).intValue()).isEqualTo(429);
        assertThat((String) JsonPath.read(throttled.body(), "$.title")).isEqualTo("Too Many Requests");
        assertThat((String) JsonPath.read(throttled.body(), "$.detail"))
                .as("the detail spells the retry signal out for humans")
                .isNotBlank();
        assertThat(throttled.header("Retry-After")).as("Retry-After: present").isNotBlank();
        long retryAfter = Long.parseLong(throttled.header("Retry-After"));
        assertThat(retryAfter)
                .as("Retry-After: whole seconds, at least one, at most one slot's refill wait")
                .isBetween(1L, 3600L / CREATIONS_PER_HOUR);
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

    /** A unique public Destination per creation — traceable to its creator when a test fails. */
    private static String destination(long githubId, int creation) {
        return "https://example.com/rate-limit/" + githubId + "/creation-" + creation;
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

    /** The 401 problem+json of a request whose Bearer credential failed closed (D9). */
    private static void assertUnauthorizedProblemJson(Exchange exchange) {
        assertThat(exchange.status()).isEqualTo(401);
        assertThat(exchange.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(exchange.body(), "$.status")).intValue()).isEqualTo(401);
        assertThat((String) JsonPath.read(exchange.body(), "$.title")).isEqualTo("Unauthorized");
    }
}
