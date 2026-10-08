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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The post-merge guard of the #5 × #6 seam (the #5 merge recipe, from #6's
 * report): Click counting and deactivation both landed on the same follow
 * path, so the merged behavior is pinned end-to-end over HTTP — two live
 * follows count two, the owner's deactivation answers 204, a follow after it
 * answers the bodyless 410 that counts nothing, and the owner's list entry
 * shows the Click count frozen at two with {@code deactivated: true} — the
 * Click is a property of the live Short Link (D3), not of the Slug.
 *
 * <p>Everything rides the one HTTP seam (G3): the application on a random
 * port, sessions in a real SQLite database, GitHub mocked at its HTTP
 * boundary (the #2/#3 base class's dance). Where {@code DeactivationHttpTest}
 * guards the 410 path against the increment through the {@code click_count}
 * column, this guard pins the same fact through the surface #5 shipped: the
 * owner's list at {@code GET /api/links}.
 */
class DeactivatedClickCountHttpTest extends AbstractGitHubSignInHttpTest {

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

    @Test
    void deactivationFreezesTheClickCountAndFlagsTheEntry() throws Exception {
        String key = bearerKeyFor(4701, "frozen-at-two");
        String slug = slugOf(createWithBearer(key, "https://example.com/freeze-after-two"));

        // two public follows while the Short Link is live: both count (D3)
        HttpClient visitor = noRedirects();
        for (int follow = 1; follow <= 2; follow++) {
            Exchange clicked = get(visitor, appUrl("/" + slug));
            assertThat(clicked.status()).as("live follow %d redirects the visitor", follow)
                    .isEqualTo(302);
        }

        // the owner deactivates their own Short Link: one-way, no undo (D14)
        Exchange deactivation = deleteWithBearer(key, slug);
        assertThat(deactivation.status()).as("the owner's key deactivates their own Short Link")
                .isEqualTo(204);

        // a third follow answers the bodyless 410 — and counts nothing
        Exchange gone = get(visitor, appUrl("/" + slug));
        assertThat(gone.status()).as("a Deactivated Short Link answers 410 Gone").isEqualTo(410);
        assertThat(gone.body()).as("the 410 is bodyless (Q16)").isEmpty();
        assertThat(gone.location()).as("a Deactivated Short Link sends nowhere").isNull();

        // the owner's list pins the merged behavior over HTTP
        Map<String, Object> entry = theListedEntry(key, slug);
        assertThat(((Number) entry.get("click_count")).longValue())
                .as("the Click count is frozen at the two live follows")
                .isEqualTo(2L);
        assertThat(entry.get("deactivated"))
                .as("the listed entry shows the Short Link as Deactivated")
                .isEqualTo(true);
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

    /** The owner's listed entry for one Slug — their one Short Link, found in their list. */
    private Map<String, Object> theListedEntry(String key, String slug) throws Exception {
        Exchange list = getWithBearer(noRedirects(), appUrl("/api/links"), key);
        assertThat(list.status()).as("the owner's list answers 200").isEqualTo(200);
        return theEntry(entriesOf(list.body()), slug);
    }

    private static Map<String, Object> theEntry(List<Map<String, Object>> entries, String slug) {
        return entries.stream()
                .filter(entry -> slug.equals(entry.get("slug")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Slug " + slug + " is not listed"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(String body) {
        return (List<Map<String, Object>>) JsonPath.read(body, "$");
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
}
