package com.urlshortener.user;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
 * The browser surface of issue #19 over the real HTTP boundary — the plan on the
 * issue, verbatim: a Visitor is redirected from the home page to sign-in; a
 * signed-in User sees the Destination form and the link to the key page; the
 * form (session cookie + its CSRF token) creates a Short Link owned by the
 * session User, which any Visitor can follow; an invalid Destination re-renders
 * the form with #4's type codes and creates nothing; the form POST refuses a
 * missing CSRF token; and the Bearer-authenticated API behaves exactly as #4
 * built it.
 *
 * <p>Everything rides the one HTTP seam: the application on a random port,
 * sessions in a real SQLite database, GitHub mocked at its HTTP boundary (the
 * #2/#3 base class's dance). The one permitted non-HTTP assertion is the
 * storage check of the plan's ownership item — plus a links-table count that
 * proves the invalid Destination created nothing (a creation that never happened
 * has no Slug to follow). The class lives beside the other browser-flow tests
 * in this package because the sign-in base is package-private here.
 */
class BrowserCreateHttpTest extends AbstractGitHubSignInHttpTest {

    private static final Path DATABASE_FILE = newSqliteFile();
    private static final MockWebServer GIT_HUB = startMockGitHub();

    /** D2: the Slug is 7 base62 characters. */
    private static final Pattern SLUG_SHAPE = Pattern.compile("[A-Za-z0-9]{7}");

    /** The short_url on a form-created link, for exact page assertions. */
    private static final String BASE_URL = "http://browser.test";

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registerTestProperties(registry, DATABASE_FILE, GIT_HUB);
        registry.add("app.base-url", () -> BASE_URL);
    }

    @AfterAll
    static void shutDown() {
        tearDown(GIT_HUB, DATABASE_FILE);
    }

    /** Reaches behind the HTTP seam for the permitted non-HTTP assertions. */
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aVisitorIsRedirectedFromTheHomePageToSignIn() throws Exception {
        // plan item 1: the home page is User-only; a Visitor is sent to sign in,
        // never a 401 — it is a page for browsers
        Exchange home = get(noRedirects(), appUrl("/"));
        assertThat(home.status()).isEqualTo(302);
        assertThat(home.location()).endsWith("/login");
    }

    @Test
    void theSignedInHomePageShowsTheCreateFormAndTheKeyLink() throws Exception {
        // plan item 2: after the sign-in dance, the home page carries the
        // Destination form and the anchor to the key page — the API Key is
        // discoverable — plus the signed-in indicator, and the login page links
        // the signed-in User back to the home page
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(5101, "home-user"));
        HttpClient client = noRedirects();

        Exchange home = get(client, appUrl("/"), signIn.sessionCookie());
        assertThat(home.status()).as("the home page renders for the signed-in User").isEqualTo(200);
        assertThat(home.body())
                .as("the form posts the Destination to the create endpoint")
                .contains("action=\"/shorten\"")
                .contains("method=\"post\"")
                .contains("name=\"destination\"");
        assertThat(home.body())
                .as("the API Key is discoverable from the home page")
                .contains("href=\"/me/key\"");
        assertThat(home.body())
                .as("the signed-in indicator shows")
                .contains("Signed in as")
                .contains("home-user");

        // discoverability, the other way round: the login page's signed-in
        // section links to the home page
        Exchange login = get(client, appUrl("/login"), signIn.sessionCookie());
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body())
                .as("the signed-in login page links to the home page")
                .contains("href=\"/\"");
    }

    @Test
    void creatingViaTheFormRendersTheShortUrlAndTheBearerHint() throws Exception {
        // plan item 3: the form POST (session cookie + the form's CSRF token)
        // creates the Short Link and renders the result: the short_url, plus
        // the copy-able curl/Postman hint in the Bearer scheme
        FormCreation created = createViaForm(5102, "form-creator", "https://example.com/created-in-the-browser");

        assertThat(created.page().body())
                .as("the result page shows the short_url")
                .contains(BASE_URL + "/" + created.slug());
        assertThat(created.page().body())
                .as("the hint shows the API seam and the Bearer scheme")
                .contains(BASE_URL + "/api/links")
                .contains("Authorization: Bearer")
                .contains("curl")
                .contains("Postman");
    }

    @Test
    void theFormCreatedLinkIsOwnedByTheSessionUser() throws Exception {
        // plan item 4 — the one permitted non-HTTP assertion: the browser
        // creation is attributed to the session User's users.id, the same
        // ownership rule the API enforces
        FormCreation created = createViaForm(5103, "owner-checker", "https://example.com/owned-from-the-form");

        long sessionUserId = ((Number) JsonPath
                .read(get(noRedirects(), appUrl("/me"), created.signIn().sessionCookie()).body(), "$.id"))
                .longValue();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT destination, owner FROM links WHERE slug = ?", created.slug());
        assertThat(row.get("owner"))
                .as("the form-created link is attributed to the session User's users.id")
                .isEqualTo(String.valueOf(sessionUserId));
        assertThat(row.get("destination")).isEqualTo("https://example.com/owned-from-the-form");
    }

    @Test
    void theFormCreatedLinkFollowsForAnyVisitor() throws Exception {
        // plan item 5: a Visitor with no credentials follows the form-created
        // link exactly as an API-created one (ADR-0001: a 302)
        FormCreation created = createViaForm(5104, "followable", "https://example.com/follow-from-the-form");

        Exchange follow = get(noRedirects(), appUrl("/" + created.slug()));
        assertThat(follow.status()).as("the form-created link follows for anyone").isEqualTo(302);
        assertThat(follow.location()).isEqualTo("https://example.com/follow-from-the-form");
    }

    @Test
    void anInvalidDestinationReRendersTheFormWithTheTypeCodes() throws Exception {
        // plan item 6: a javascript: Destination creates nothing; the form
        // re-renders with #4's type code and its detail — and the re-render
        // answers 400, the same status family the API gives the same mistake
        // (this test pins that choice)
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(5105, "bad-destination"));
        HttpClient client = noRedirects();
        String destination = "javascript:alert('shortener')";
        Exchange home = get(client, appUrl("/"), signIn.sessionCookie());
        assertThat(home.status()).isEqualTo(200);

        Exchange result = postForm(client, appUrl("/shorten"),
                Map.of("destination", destination, "_csrf", csrfTokenFrom(home.body())),
                signIn.sessionCookie());

        assertThat(result.status())
                .as("the re-render answers 400, the same status family as the API's 400")
                .isEqualTo(400);
        assertThat(result.body())
                .as("the re-render shows #4's type code and its detail")
                .contains("invalid_scheme")
                .contains("absolute http or https URL");
        assertThat(result.body())
                .as("the form is still there, ready again")
                .contains("name=\"destination\"");
        assertThat(result.body())
                .as("no short_url is rendered — nothing was created")
                .doesNotContain(BASE_URL);

        // no link was created: nothing in the links table points there (the
        // second permitted non-HTTP assertion — a creation that never happened
        // has no Slug to follow)
        Integer linksToIt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM links WHERE destination = ?", Integer.class, destination);
        assertThat(linksToIt).as("no link was created for the invalid Destination").isZero();
    }

    @Test
    void theShortenFormRequiresTheCsrfToken() throws Exception {
        // plan item 7: these pages are not under /api/**, so CSRF stays on — a
        // form POST without the token is refused, even with a valid session
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(5106, "csrf-checker"));
        HttpClient client = noRedirects();
        get(client, appUrl("/"), signIn.sessionCookie());

        Exchange post = postForm(client, appUrl("/shorten"),
                Map.of("destination", "https://example.com/no-csrf-token"), signIn.sessionCookie());

        assertThat(post.status())
                .as("a form POST without the CSRF token is refused")
                .isEqualTo(403);
        Integer created = jdbc.queryForObject(
                "SELECT COUNT(*) FROM links WHERE destination = ?", Integer.class,
                "https://example.com/no-csrf-token");
        assertThat(created).as("the refused POST created nothing").isZero();
    }

    @Test
    void theApiSeamStillBehavesAsTicket4BuiltIt() throws Exception {
        // plan item 8: the UI is a thin skin — the API stays pure JSON,
        // Bearer-authenticated, for machines, exactly as #4 built it
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(5107, "api-still"));
        HttpClient client = noRedirects();
        String key = theIssuedKey(get(client, appUrl("/me/key"), signIn.sessionCookie()).body());

        // the Bearer key alone still creates, with the documented body
        Exchange creation = postJsonWithBearer(noRedirects(), appUrl("/api/links"),
                "{\"destination\":\"https://example.com/still-the-api-way\"}", key);
        assertThat(creation.status()).isEqualTo(201);
        assertThat(creation.header("Content-Type")).contains("application/json");
        String slug = (String) JsonPath.read(creation.body(), "$.slug");
        assertThat(slug).matches(SLUG_SHAPE);
        assertThat((String) JsonPath.read(creation.body(), "$.short_url")).isEqualTo(BASE_URL + "/" + slug);

        // no credential is still a 401 problem+json, never a login-page redirect
        Exchange anonymous = postJson(noRedirects(), appUrl("/api/links"),
                "{\"destination\":\"https://example.com/no-credential\"}");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.location()).isNull();
        assertThat(anonymous.header("Content-Type")).startsWith("application/problem+json");
        assertThat(((Number) JsonPath.read(anonymous.body(), "$.status")).intValue()).isEqualTo(401);
        assertThat((String) JsonPath.read(anonymous.body(), "$.title")).isEqualTo("Unauthorized");
    }

    /** The full browser creation: sign in, load the form's CSRF token, POST it. */
    private FormCreation createViaForm(long githubId, String login, String destination) throws Exception {
        SignInResult signIn = signInViaGitHub(GIT_HUB, githubProfile(githubId, login));
        HttpClient client = noRedirects();

        Exchange home = get(client, appUrl("/"), signIn.sessionCookie());
        assertThat(home.status()).as("the home page renders for the signed-in User").isEqualTo(200);

        Exchange result = postForm(client, appUrl("/shorten"),
                Map.of("destination", destination, "_csrf", csrfTokenFrom(home.body())),
                signIn.sessionCookie());
        assertThat(result.status())
                .as("the form creates the Short Link and renders the result")
                .isEqualTo(200);

        return new FormCreation(result, slugFrom(result.body()), signIn);
    }

    /** The created link's presence on the page: its short_url, verbatim. */
    private static String slugFrom(String pageBody) {
        Matcher shortUrl = Pattern.compile(Pattern.quote(BASE_URL) + "/([A-Za-z0-9]{7})").matcher(pageBody);
        if (!shortUrl.find()) {
            throw new IllegalStateException("no short_url on the result page: " + pageBody);
        }
        return shortUrl.group(1);
    }

    /** The one key shown on a page response that issued it. */
    private static String theIssuedKey(String pageBody) {
        Matcher issued = Pattern.compile("ush_[A-Za-z0-9]{32}").matcher(pageBody);
        if (!issued.find()) {
            throw new IllegalStateException("no issued key found on the key page: " + pageBody);
        }
        return issued.group();
    }

    private static String githubProfile(long githubId, String login) {
        return "{\"id\":" + githubId + ",\"login\":\"" + login + "\",\"name\":\"" + login
                + " User\",\"email\":\"" + login + "@example.com\"}";
    }

    /** POST /api/links with the Bearer key as the only credential (issue #4). */
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

    private record FormCreation(Exchange page, String slug, SignInResult signIn) {
    }
}
