package com.urlshortener.user;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for the GitHub sign-in HTTP tests (issue #2). Everything is driven through the real
 * HTTP boundary: the application on a random port, sessions in a real SQLite database in a
 * per-run temp file, and GitHub mocked at its HTTP boundary by a MockWebServer authorization
 * server driving the full authorization-code flow. No real GitHub credentials anywhere.
 *
 * <p>Subclasses declare their own {@link MockWebServer} and SQLite temp file (fresh per test
 * run, per class) and register them with {@link #registerTestProperties}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractGitHubSignInHttpTest {

    protected static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String ACCESS_TOKEN = "test-access-token";
    private static final String AUTHORIZATION_CODE = "test-authorization-code";
    /** Spring Session's session cookie name (replaces JSESSIONID). */
    private static final String SESSION_COOKIE = "SESSION";

    @LocalServerPort
    int port;

    static Path newSqliteFile() {
        try {
            return Files.createTempFile("urlshortener-ticket2-", ".db");
        }
        catch (IOException exception) {
            throw new IllegalStateException("could not create the per-run SQLite database file", exception);
        }
    }

    static MockWebServer startMockGitHub() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
        }
        catch (IOException exception) {
            throw new IllegalStateException("could not start the mock GitHub server", exception);
        }
        return server;
    }

    /**
     * Points the application under test at the per-run SQLite database and at the mock GitHub
     * authorization server instead of github.com.
     */
    static void registerTestProperties(DynamicPropertyRegistry registry, Path databaseFile, MockWebServer gitHub) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + toForwardSlashes(databaseFile));
        registry.add("spring.security.oauth2.client.registration.github.client-id", () -> CLIENT_ID);
        registry.add("spring.security.oauth2.client.registration.github.client-secret", () -> CLIENT_SECRET);
        registry.add("spring.security.oauth2.client.provider.github.authorization-uri",
                () -> gitHub.url("/oauth/authorize").toString());
        registry.add("spring.security.oauth2.client.provider.github.token-uri",
                () -> gitHub.url("/oauth/token").toString());
        registry.add("spring.security.oauth2.client.provider.github.user-info-uri",
                () -> gitHub.url("/user").toString());
        registry.add("spring.security.oauth2.client.provider.github.user-name-attribute", () -> "id");
    }

    static void tearDown(MockWebServer gitHub, Path databaseFile) {
        try {
            gitHub.shutdown();
        }
        catch (IOException exception) {
            // a failed mock-server shutdown never fails the test run
        }
        try {
            Files.deleteIfExists(databaseFile);
        }
        catch (IOException exception) {
            // The cached Spring context may keep the SQLite file open (Windows locks open
            // files); the leftover temp file is harmless.
        }
    }

    /**
     * Installs the mock authorization server behavior: the authorization endpoint echoes the
     * state back to the callback, the token endpoint exchanges any code, and the user-info
     * endpoint serves the given GitHub profile JSON.
     */
    static void mockGitHubUser(MockWebServer gitHub, String userInfoJson) {
        gitHub.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                HttpUrl url = request.getRequestUrl();
                if (url == null) {
                    return new MockResponse().setResponseCode(404);
                }
                if ("/oauth/authorize".equals(url.encodedPath())) {
                    String redirectUri = url.queryParameter("redirect_uri");
                    String state = url.queryParameter("state");
                    return new MockResponse().setResponseCode(302)
                            .setHeader("Location", redirectUri + "?code=" + AUTHORIZATION_CODE + "&state=" + state);
                }
                if ("/oauth/token".equals(url.encodedPath())) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"" + ACCESS_TOKEN
                                    + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"read:user\"}");
                }
                if ("/user".equals(url.encodedPath())) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(userInfoJson);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
    }

    String appUrl(String path) {
        return "http://localhost:" + port + path;
    }

    /** Resolves a Location header against the application under test. */
    String resolve(String location) {
        return location.startsWith("http") ? location : appUrl(location);
    }

    /** One complete sign-in round trip: initiation, authorization, callback, session cookie. */
    SignInResult signInViaGitHub(MockWebServer gitHub, String userInfoJson) throws Exception {
        mockGitHubUser(gitHub, userInfoJson);
        HttpClient client = noRedirects();

        Exchange initiation = get(client, appUrl("/oauth2/authorization/github"));
        assertThat(initiation.status()).as("the initiation endpoint redirects to the authorization endpoint")
                .isEqualTo(302);

        Exchange authorized = get(client, initiation.location());
        assertThat(authorized.status()).as("the authorization endpoint redirects back to the callback")
                .isEqualTo(302);

        Exchange callback = get(client, resolve(authorized.location()), initiation.sessionCookie());
        assertThat(callback.status()).as("the callback completes sign-in and redirects to the landing page")
                .isEqualTo(302);

        return new SignInResult(callback.sessionCookie(), callback.location());
    }

    record SignInResult(String sessionCookie, String landingLocation) {
    }

    record Exchange(int status, Map<String, List<String>> headers, String body) {

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        String location() {
            return header("Location");
        }

        /** The latest SESSION cookie value, or null if the response did not issue one. */
        String sessionCookie() {
            String latest = null;
            for (String value : headers.getOrDefault("set-cookie", List.of())) {
                if (value.startsWith(SESSION_COOKIE + "=")) {
                    latest = value;
                }
            }
            if (latest == null) {
                return null;
            }
            String cookie = latest.substring((SESSION_COOKIE + "=").length());
            int attributes = cookie.indexOf(';');
            return attributes >= 0 ? cookie.substring(0, attributes) : cookie;
        }
    }

    static HttpClient noRedirects() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static Exchange get(HttpClient client, String url, String... sessionCookies) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        String cookie = cookieHeader(sessionCookies);
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return exchange(client, builder.build());
    }

    static Exchange postForm(HttpClient client, String url, Map<String, String> form, String... sessionCookies)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)));
        String cookie = cookieHeader(sessionCookies);
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return exchange(client, builder.build());
    }

    /** Extracts the CSRF token from the hidden input that Thymeleaf renders into the form. */
    static String csrfTokenFrom(String html) {
        Matcher tag = Pattern.compile("<input[^>]*_csrf[^>]*>").matcher(html);
        if (!tag.find()) {
            throw new IllegalStateException("no CSRF token input found in the page: " + html);
        }
        Matcher value = Pattern.compile("value=\"([^\"]+)\"").matcher(tag.group());
        if (!value.find()) {
            throw new IllegalStateException("no value in the CSRF token input: " + tag.group());
        }
        return value.group(1);
    }

    /** Decodes a URL's query parameters. */
    static Map<String, String> queryOf(String url) {
        int query = url.indexOf('?');
        return Arrays.stream(url.substring(query + 1).split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> urlDecode(pair[0]),
                        pair -> pair.length > 1 ? urlDecode(pair[1]) : ""));
    }

    private static Exchange exchange(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Exchange(response.statusCode(), response.headers().map(), response.body());
    }

    /** Builds the Cookie header: SESSION=value pairs, ready to be sent back. */
    private static String cookieHeader(String... sessionCookieValues) {
        List<String> present = Arrays.stream(sessionCookieValues).filter(Objects::nonNull).toList();
        return present.isEmpty() ? null
                : present.stream().map(value -> SESSION_COOKIE + "=" + value).collect(Collectors.joining("; "));
    }

    private static String urlEncode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String toForwardSlashes(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
