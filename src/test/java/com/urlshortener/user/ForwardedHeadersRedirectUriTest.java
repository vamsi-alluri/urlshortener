package com.urlshortener.user;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #16: behind the HTTPS proxy, the OAuth redirect_uri must be built from the
 * forwarded scheme and host — not from the literal HTTP connection the app receives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersRedirectUriTest {

    @DynamicPropertySource
    static void perRunDatabaseAndRegistration(DynamicPropertyRegistry registry) throws IOException {
        var dir = Files.createTempDirectory("urlshortener-issue16");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dir.resolve("issue16.sqlite"));
        // A registration with an unreachable authorization endpoint: the initiation 302 is
        // never followed (Redirect.NEVER), so no mock server is needed — only the Location.
        registry.add("spring.security.oauth2.client.registration.github.client-id", () -> "test-client");
        registry.add("spring.security.oauth2.client.registration.github.client-secret", () -> "test-secret");
        registry.add("spring.security.oauth2.client.provider.github.authorization-uri",
                () -> "https://auth.example/authorize");
    }

    @LocalServerPort
    int port;

    @Test
    void theOAuthRedirectUriHonoursForwardedProtoAndHost() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/oauth2/authorization/github"))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "short.example")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith("https://auth.example/authorize");
        assertThat(location).contains("client_id=test-client");
        // #16: https from the forwarded scheme, the forwarded host, the standard callback path.
        // (Spring writes the redirect_uri literally into the query, not percent-encoded.)
        assertThat(location).contains("redirect_uri=https://short.example/login/oauth2/code/github");
    }
}
