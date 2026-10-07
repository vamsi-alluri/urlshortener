package com.urlshortener.link;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreateShortLinkTest extends LinkApiTestBase {

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    @Test
    void creationReturnsSlugShortUrlAndDestination() throws Exception {
        String destination = "https://example.com/some/long/path?query=value#fragment";

        String body = createShortLinkTo(destination);

        String slug = field(body, "$.slug");
        assertThat(slug).matches("[A-Za-z0-9]{7}");                       // D2: base62, exactly 7
        assertThat(field(body, "$.short_url")).isEqualTo(BASE_URL + "/" + slug);
        assertThat(field(body, "$.destination")).isEqualTo(destination);
    }

    @Test
    void repeatedCreationsYieldDistinctSlugs() throws Exception {
        Set<String> slugs = new HashSet<>();
        for (int creation = 0; creation < 12; creation++) {
            String body = createShortLinkTo("https://example.com/destinations/" + creation);
            assertThat(field(body, "$.slug")).matches("[A-Za-z0-9]{7}");
            slugs.add(field(body, "$.slug"));
        }
        assertThat(slugs).hasSize(12);
    }

    @Test
    void identicalDestinationsYieldDistinctShortLinksThatBothResolve() throws Exception {
        String destination = "https://example.com/same-destination-everywhere";

        String firstSlug = field(createShortLinkTo(destination), "$.slug");
        String secondSlug = field(createShortLinkTo(destination), "$.slug");

        // Counts and ownership must never blend across the two Short Links.
        assertThat(firstSlug).isNotEqualTo(secondSlug);

        mockMvc.perform(get("/" + firstSlug))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, destination));
        mockMvc.perform(get("/" + secondSlug))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, destination));
    }

    @Test
    void creationRejectsADestinationThatIsNotAnHttpUrl() throws Exception {
        createExpectingBadRequest("{\"destination\":\"ftp://example.com/file\"}");
    }

    @Test
    void creationRejectsABlankDestination() throws Exception {
        createExpectingBadRequest("{\"destination\":\"\"}");
    }

    private void createExpectingBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }
}
