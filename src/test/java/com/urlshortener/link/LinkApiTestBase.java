package com.urlshortener.link;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for the Short Link API tests. One seam — the HTTP API —
 * exercised through MockMvc against a real SQLite file created fresh for each
 * test run (issue #1, Testing Decisions).
 *
 * <p>Servlet filters are bypassed ({@code addFilters = false}) because Spring
 * Security's default chain locks every route until ticket #2 lands its
 * configuration; authentication is not this ticket's seam.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
abstract class LinkApiTestBase {

    /** Fixed base URL so short_url assertions are deterministic. */
    static final String BASE_URL = "http://links.test";

    private static final Path DATABASE_FILE;

    static {
        try {
            // A real SQLite file per test run. Not cleaned up deliberately:
            // deleting SQLite files under a pooled connection is flaky on Windows.
            DATABASE_FILE = Files.createTempDirectory("urlshortener-link-api-")
                    .resolve("links.sqlite");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void isolatedSqliteDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_FILE);
        registry.add("app.base-url", () -> BASE_URL);
    }

    @Autowired
    protected MockMvc mockMvc;

    /**
     * Creates a Short Link to the given Destination and returns the 201 body.
     */
    protected String createShortLinkTo(String destination) throws Exception {
        return mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destination\":\"" + destination + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Reads a single string field from a response body.
     */
    protected static String field(String body, String jsonPath) {
        return JsonPath.read(body, jsonPath);
    }
}
