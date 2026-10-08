package com.urlshortener.user;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Click counting and the owner's list over the real HTTP boundary (issue #5,
 * the plan on the issue): counts start at zero, every live follow counts (three
 * public follows list as three), the keyholder's own list at
 * {@code GET /api/links} carries every created Short Link with every D12 field,
 * one owner never sees another's, {@code limit}/{@code offset} page the list
 * and the limit clamps at 100, every entry carries {@code deactivated} (false
 * everywhere today — the flagged case is #6's suite, post-merge), and a request
 * without a key is 401 problem+json.
 *
 * <p>The class lives in {@code com.urlshortener.user} deliberately: the sign-in
 * dance (mock GitHub, key page, Bearer key) is package-private there, and this
 * feature is inseparable from the credential — real keys for real owners, the
 * way {@code AuthenticatedCreationHttpTest} (issue #4) already rides the same
 * seam. Short Links are created the way owners create them ({@code POST
 * /api/links} with the Bearer key) and followed the way visitors follow them
 * (no credentials at all). Every test signs in its own fresh User (a 42xx
 * GitHub identity), so the per-run database shared by this class never blends
 * one test's Short Links into another's list.
 */
class OwnerLinksHttpTest extends AbstractGitHubSignInHttpTest {

    /**
     * The pagination plans create up to 101 Short Links with one key — over
     * the default 60-per-hour creation allowance (#7, which landed on main
     * after this branch forked) — so this run's keys get a raised allowance
     * through the same documented config knob (D15: a config value, not a
     * policy), the way {@code RateLimitHttpTest} sets it low.
     */
    private static final int CREATIONS_PER_HOUR = 1_000;

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

    /** Plan 1: a key-created Short Link lists with click_count 0. */
    @Test
    void aKeyCreatedShortLinkListsWithClickCountZero() throws Exception {
        String key = bearerKeyFor(4201, "zero-clicks");
        String slug = slugOf(createWithBearer(key, "https://example.com/never-followed-yet"));

        Map<String, Object> entry = theListedEntry(key, slug);

        assertThat(((Number) entry.get("click_count")).longValue())
                .as("a Short Link is created with zero Clicks")
                .isEqualTo(0L);
    }

    /** Plan 2: every live follow counts — three public follows list as three. */
    @Test
    void everyLiveFollowIsCounted() throws Exception {
        String key = bearerKeyFor(4202, "count-my-follows");
        String destination = "https://example.com/follow-me-three-times";
        String slug = slugOf(createWithBearer(key, destination));

        // three public follows: no session, no key, no credentials of any kind
        HttpClient visitor = noRedirects();
        for (int follow = 1; follow <= 3; follow++) {
            Exchange clicked = get(visitor, appUrl("/" + slug));
            assertThat(clicked.status()).as("follow %d still redirects the visitor", follow)
                    .isEqualTo(302);
            assertThat(clicked.location()).isEqualTo(destination);
        }

        assertThat(((Number) theListedEntry(key, slug).get("click_count")).longValue())
                .as("three live follows counted")
                .isEqualTo(3L);
    }

    /** Plan 3: the owner's list — 200, every D12 field, every created link present. */
    @Test
    void theOwnersListCarriesEveryCreatedShortLinkWithEveryField() throws Exception {
        String key = bearerKeyFor(4203, "list-everything");
        Exchange firstCreation = createWithBearer(key, "https://example.com/first-listed");
        Exchange secondCreation = createWithBearer(key, "https://example.com/second-listed");

        Exchange list = getWithBearer(noRedirects(), appUrl("/api/links"), key);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.header("Content-Type")).contains("application/json");

        List<Map<String, Object>> entries = entriesOf(list.body());
        for (Map<String, Object> entry : entries) {
            assertThat(entry.keySet())
                    .as("every entry carries exactly D12's fields")
                    .containsExactlyInAnyOrder("slug", "short_url", "destination",
                            "click_count", "created_at", "deactivated");
        }
        assertThat(slugsOf(entries))
                .as("every created Short Link is listed")
                .contains(slugOf(firstCreation), slugOf(secondCreation));

        Map<String, Object> first = theEntry(entries, slugOf(firstCreation));
        assertThat(first.get("short_url"))
                .isEqualTo((String) JsonPath.read(firstCreation.body(), "$.short_url"));
        assertThat(first.get("destination")).isEqualTo("https://example.com/first-listed");
        assertThat(((Number) first.get("click_count")).longValue()).isEqualTo(0L);
        assertThat((String) first.get("created_at")).isNotBlank();
        assertThatCode(() -> Instant.parse((String) first.get("created_at")))
                .as("created_at is ISO-8601 UTC text")
                .doesNotThrowAnyException();
        assertThat(first.get("deactivated")).isEqualTo(false);
    }

    /** Plan 4: two owners each create; each list shows only their own. */
    @Test
    void anOwnerSeesOnlyTheirOwnShortLinks() throws Exception {
        String firstKey = bearerKeyFor(4204, "isolated-first");
        String secondKey = bearerKeyFor(4205, "isolated-second");

        String firstSlug = slugOf(createWithBearer(firstKey, "https://example.com/firsts-own"));
        String secondSlug = slugOf(createWithBearer(secondKey, "https://example.com/seconds-own"));

        assertThat(slugsOf(listEntriesOf(firstKey, "")))
                .as("the first owner's list holds exactly their own links")
                .containsExactly(firstSlug);
        assertThat(slugsOf(listEntriesOf(secondKey, "")))
                .as("the second owner's list holds exactly their own links")
                .containsExactly(secondSlug);
    }

    /** Plan 5: a small limit pages through every Short Link exactly once. */
    @Test
    void aSmallLimitPagesThroughEveryShortLinkExactlyOnce() throws Exception {
        String key = bearerKeyFor(4206, "small-pages");
        List<String> created = new ArrayList<>();
        for (int creation = 0; creation < 5; creation++) {
            created.add(slugOf(createWithBearer(key, "https://example.com/paged-" + creation)));
        }

        List<String> seenByPages = new ArrayList<>();
        for (int offset = 0; offset <= 4; offset += 2) {
            List<Map<String, Object>> page = listEntriesOf(key, "?limit=2&offset=" + offset);
            assertThat(page).as("a limit of 2 returns at most 2 entries")
                    .hasSizeLessThanOrEqualTo(2);
            page.forEach(entry -> seenByPages.add((String) entry.get("slug")));
        }
        assertThat(seenByPages)
                .as("the pages tile every created Short Link exactly once")
                .containsExactlyInAnyOrderElementsOf(created);

        assertThat(listEntriesOf(key, "?limit=2&offset=100"))
                .as("paging past the end is an empty page")
                .isEmpty();
    }

    /** Plan 5: the max clamps at 100 — and the default page is 100 too. */
    @Test
    void theLimitClampsAtOneHundred() throws Exception {
        String key = bearerKeyFor(4207, "clamped-pages");
        for (int creation = 0; creation < 101; creation++) {
            createWithBearer(key, "https://example.com/clamped-" + creation);
        }

        assertThat(listEntriesOf(key, ""))
                .as("the default page holds 100 of the 101")
                .hasSize(100);
        assertThat(listEntriesOf(key, "?limit=500"))
                .as("a limit over the max clamps at 100, not 500")
                .hasSize(100);
        assertThat(listEntriesOf(key, "?limit=100&offset=100"))
                .as("the 101st Short Link waits on the next page")
                .hasSize(1);
    }

    /** Plan 6: deactivated is present — false everywhere today; #6 owns the true case. */
    @Test
    void everyEntryCarriesDeactivatedFalseToday() throws Exception {
        String key = bearerKeyFor(4208, "nothing-deactivated-yet");
        createWithBearer(key, "https://example.com/still-live-one");
        createWithBearer(key, "https://example.com/still-live-two");

        List<Map<String, Object>> entries = listEntriesOf(key, "");
        assertThat(entries).as("both of the owner's Short Links are listed").hasSize(2);
        assertThat(entries).allSatisfy(entry -> assertThat(entry.get("deactivated"))
                .as("no Short Link is Deactivated today")
                .isEqualTo(false));
    }

    /** Plan 7: GET /api/links without a key is 401 problem+json. */
    @Test
    void listingWithoutAKeyIs401ProblemJson() throws Exception {
        Exchange list = get(noRedirects(), appUrl("/api/links"));
        assertThat(list.status()).isEqualTo(401);
        assertThat(list.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(list.body(), "$.status")).intValue()).isEqualTo(401);
        assertThat((String) JsonPath.read(list.body(), "$.title")).isEqualTo("Unauthorized");
    }

    /**
     * Beyond the plan, but the house contract (README: every error is RFC 7807
     * problem+json) demands it: a limit or offset that is not an integer
     * answers 400 problem+json, not a generic error page.
     */
    @Test
    void malformedPaginationParametersAnswer400ProblemJson() throws Exception {
        String key = bearerKeyFor(4209, "strict-pages");
        createWithBearer(key, "https://example.com/well-formed-pages-only");

        for (String query : new String[] {"?limit=many", "?offset=next-page"}) {
            Exchange malformed = getWithBearer(noRedirects(), appUrl("/api/links" + query), key);
            assertThat(malformed.status()).as("a non-integer page parameter: %s", query)
                    .isEqualTo(400);
            assertThat(malformed.header("Content-Type")).startsWith("application/problem+json");
            assertThat(((Number) JsonPath.read(malformed.body(), "$.status")).intValue())
                    .isEqualTo(400);
            assertThat((String) JsonPath.read(malformed.body(), "$.title")).isEqualTo("Bad Request");
        }
    }

    /** One full sign-in dance for a fresh User, returning their first-issued key (D7). */
    private String bearerKeyFor(long githubId, String login) throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(githubId, login));
        Exchange keyPage = get(noRedirects(), appUrl("/me/key"), signIn.sessionCookie());
        assertThat(keyPage.status()).as("the key page issues the key on first visit").isEqualTo(200);
        return theIssuedKey(keyPage.body());
    }

    /** POST /api/links with the Bearer key as the only credential — how an owner creates. */
    private Exchange createWithBearer(String key, String destination) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(appUrl("/api/links")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"destination\":\"" + destination + "\"}"))
                .build();
        HttpResponse<String> response =
                noRedirects().send(request, HttpResponse.BodyHandlers.ofString());
        Exchange creation =
                new Exchange(response.statusCode(), response.headers().map(), response.body());
        assertThat(creation.status()).as("the key creates the Short Link").isEqualTo(201);
        return creation;
    }

    /** GET /api/links with the Bearer key as the only credential — the owner's list. */
    private List<Map<String, Object>> listEntriesOf(String key, String query) throws Exception {
        Exchange list = getWithBearer(noRedirects(), appUrl("/api/links" + query), key);
        assertThat(list.status()).as("the owner's list answers 200").isEqualTo(200);
        return entriesOf(list.body());
    }

    /** The owner's listed entry for one Slug — their one Short Link, found in their list. */
    private Map<String, Object> theListedEntry(String key, String slug) throws Exception {
        return theEntry(listEntriesOf(key, ""), slug);
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

    private static List<String> slugsOf(List<Map<String, Object>> entries) {
        return entries.stream().map(entry -> (String) entry.get("slug")).toList();
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
