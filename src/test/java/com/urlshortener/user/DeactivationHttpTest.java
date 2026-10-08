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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner deactivation over the real HTTP boundary (issue #6, the plan on the
 * issue): the owner's Bearer key deactivates their own Short Link — one-way,
 * no undo — and following it answers a bodyless 410 that never counts a Click.
 * Another User's Slug and an unknown Slug answer the same 404 problem+json,
 * so the response leaks nothing about which Short Links exist (D14); a second
 * deactivation answers 409; and no request anywhere flips a Deactivated link
 * back.
 *
 * <p>Everything rides the one HTTP seam: the application on a random port,
 * sessions in a real SQLite database, GitHub mocked at its HTTP boundary (the
 * #2/#3 base class's dance). The one reach behind the seam is the Click-count
 * check of plan item 1: the Click surface itself arrives with #5's list
 * endpoint, so on this branch the count is observable only as the
 * {@code click_count} column — and the check guards the 410 path against #5's
 * increment landing on it; post-merge, #8's frozen-count assertion pins it
 * over HTTP instead.
 */
class DeactivationHttpTest extends AbstractGitHubSignInHttpTest {

    private static final Path DATABASE_FILE = newSqliteFile();
    private static final MockWebServer GIT_HUB = startMockGitHub();

    /** D7: ush_ + 32 base62 characters — the one shape an issued key has. */
    private static final Pattern KEY_SHAPE = Pattern.compile("ush_[A-Za-z0-9]{32}");

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registerTestProperties(registry, DATABASE_FILE, GIT_HUB);
    }

    @AfterAll
    static void shutDown() {
        tearDown(GIT_HUB, DATABASE_FILE);
    }

    /** Reaches behind the HTTP seam for the Click-count check of plan item 1. */
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theOwnerDeactivatesTheirShortLinkAndFollowsAnswerABodyless410() throws Exception {
        String ownerKey = bearerKeyFor(4601, "deactivator");
        String slug = slugOf(createWithBearer(ownerKey, "https://example.com/deactivate-me"));

        Exchange deactivation = deleteWithBearer(ownerKey, slug);
        assertThat(deactivation.status()).as("the owner's key deactivates their own Short Link")
                .isEqualTo(204);
        assertThat(deactivation.body()).as("the 204 carries no body").isEmpty();

        // a Visitor with no credential follows the Deactivated Short Link
        Exchange follow = get(noRedirects(), appUrl("/" + slug));
        assertThat(follow.status()).as("a Deactivated Short Link answers 410 Gone").isEqualTo(410);
        assertThat(follow.body()).as("the 410 is bodyless (Q16)").isEmpty();
        assertThat(follow.location()).as("a Deactivated Short Link sends nowhere").isNull();

        // the 410 path never counts a Click (plan item 1): the count column is
        // untouched — #5's increment must never land on the 410 branch
        Number clicks = jdbc.queryForObject(
                "SELECT click_count FROM links WHERE slug = ?", Number.class, slug);
        assertThat(clicks.longValue()).as("a 410 follow never increments the Click count").isZero();
    }

    @Test
    void anotherUsersSlugAndAnUnknownSlugAnswerTheSame404ProblemJson() throws Exception {
        String ownerKey = bearerKeyFor(4602, "deactivation-owner");
        String strangerKey = bearerKeyFor(4603, "deactivation-stranger");
        String ownedSlug = slugOf(createWithBearer(ownerKey, "https://example.com/not-yours-to-kill"));

        Exchange strangersAttempt = deleteWithBearer(strangerKey, ownedSlug);
        Exchange unknownAttempt = deleteWithBearer(strangerKey, "zzzzzzz");

        // the two 404s answer identically — same status, title, and detail — so
        // a stranger learns nothing about which Short Links exist (D14)
        assertNotFoundProblemJson(strangersAttempt, ownedSlug);
        assertNotFoundProblemJson(unknownAttempt, "zzzzzzz");

        // the refused attempt deactivated nothing: the owner's Short Link still resolves
        assertThat(get(noRedirects(), appUrl("/" + ownedSlug)).status())
                .as("another User's DELETE touches nothing")
                .isEqualTo(302);
    }

    @Test
    void deactivatingAnAlreadyDeactivatedShortLinkAnswers409ProblemJson() throws Exception {
        String ownerKey = bearerKeyFor(4604, "twice-deactivator");
        String slug = slugOf(createWithBearer(ownerKey, "https://example.com/only-once"));

        assertThat(deleteWithBearer(ownerKey, slug).status())
                .as("the first deactivation succeeds")
                .isEqualTo(204);

        Exchange second = deleteWithBearer(ownerKey, slug);
        assertThat(second.status()).as("deactivation is one-way: the second attempt is 409")
                .isEqualTo(409);
        assertThat(second.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(second.body(), "$.status")).intValue()).isEqualTo(409);
        assertThat((String) JsonPath.read(second.body(), "$.title")).isEqualTo("Conflict");
        assertThat((String) JsonPath.read(second.body(), "$.detail"))
                .isEqualTo("Short Link '" + slug + "' is already Deactivated.");

        // still Deactivated — the 409 changed nothing
        assertThat(get(noRedirects(), appUrl("/" + slug)).status()).isEqualTo(410);
    }

    @Test
    void noEndpointFlipsADeactivatedShortLinkBack() throws Exception {
        String ownerKey = bearerKeyFor(4605, "no-undo");
        String slug = slugOf(createWithBearer(ownerKey, "https://example.com/one-way-door"));
        assertThat(deleteWithBearer(ownerKey, slug).status()).isEqualTo(204);

        // every plausible reactivation request, with the owner's own key: none
        // exists — deactivation is one-way (the test helper is a plain method,
        // never an HTTP route)
        for (String[] candidate : new String[][] {
                {"PUT", "/api/links/" + slug},
                {"PATCH", "/api/links/" + slug},
                {"POST", "/api/links/" + slug + "/reactivate"},
                {"POST", "/api/links/" + slug + "/restore"}}) {
            Exchange attempt = requestWithBearer(noRedirects(), candidate[0], appUrl(candidate[1]),
                    ownerKey, "{\"destination\":\"https://example.com/revived\"}");
            assertThat(attempt.status())
                    .as("no route reactivates: %s %s", candidate[0], candidate[1])
                    .isIn(404, 405);
        }

        // nothing above flipped it back: the one-way door stays shut
        assertThat(get(noRedirects(), appUrl("/" + slug)).status())
                .as("the Short Link stays Deactivated after every reactivation attempt")
                .isEqualTo(410);
    }

    /** One full sign-in dance for a fresh User, returning their first-issued key (D7). */
    private String bearerKeyFor(long githubId, String login) throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(githubId, login));
        Exchange keyPage = get(noRedirects(), appUrl("/me/key"), signIn.sessionCookie());
        assertThat(keyPage.status()).as("the key page issues the key on first visit").isEqualTo(200);
        return theIssuedKey(keyPage.body());
    }

    /** POST /api/links with the Bearer key as the only credential — creation (#4). */
    private Exchange createWithBearer(String key, String destination) throws Exception {
        return requestWithBearer(noRedirects(), "POST", appUrl("/api/links"), key,
                "{\"destination\":\"" + destination + "\"}");
    }

    /** DELETE /api/links/{slug} with the Bearer key as the only credential — deactivation (#6). */
    private Exchange deleteWithBearer(String key, String slug) throws Exception {
        return requestWithBearer(noRedirects(), "DELETE", appUrl("/api/links/" + slug), key, null);
    }

    /** One Bearer-authenticated request — the machine seam (#3, D9); bodyless when the body is null. */
    private static Exchange requestWithBearer(HttpClient client, String method, String url,
            String bearerKey, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + bearerKey);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Exchange(response.statusCode(), response.headers().map(), response.body());
    }

    private static String slugOf(Exchange creation) {
        return (String) JsonPath.read(creation.body(), "$.slug");
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

    /** The 404 problem+json of a deactivation the caller may not perform (D14). */
    private static void assertNotFoundProblemJson(Exchange exchange, String slug) {
        assertThat(exchange.status()).isEqualTo(404);
        assertThat(exchange.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(exchange.body(), "$.status")).intValue()).isEqualTo(404);
        assertThat((String) JsonPath.read(exchange.body(), "$.title")).isEqualTo("Not Found");
        assertThat((String) JsonPath.read(exchange.body(), "$.detail"))
                .as("the same detail an unknown Slug answers with — no existence leak")
                .isEqualTo("No Short Link exists for Slug '" + slug + "'");
    }
}
