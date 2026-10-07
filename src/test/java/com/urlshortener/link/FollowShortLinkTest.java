package com.urlshortener.link;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FollowShortLinkTest extends LinkApiTestBase {

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    @Test
    void followingAShortLinkSendsTheVisitorToItsDestinationWith302() throws Exception {
        String destination = "https://example.com/the/destination";
        String slug = field(createShortLinkTo(destination), "$.slug");

        mockMvc.perform(get("/" + slug))
                .andExpect(status().isFound())                                   // 302, never 301 (ADR-0001)
                .andExpect(header().string(HttpHeaders.LOCATION, destination))
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));    // no cache headers (ADR-0001)
    }

    @Test
    void anUnknownSlugReturns404ProblemDetails() throws Exception {
        mockMvc.perform(get("/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }
}
