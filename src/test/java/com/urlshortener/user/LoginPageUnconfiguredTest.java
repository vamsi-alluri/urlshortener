package com.urlshortener.user;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Issue #14: on a server with no GitHub OAuth app configured, the sign-in page must not
 * advertise a dead link — it says sign-in is unavailable instead. This context deliberately
 * configures no OAuth registration (the mock-server dance from #2's tests is what wires one).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginPageUnconfiguredTest {

    @DynamicPropertySource
    static void perRunDatabase(DynamicPropertyRegistry registry) throws IOException {
        var dir = Files.createTempDirectory("urlshortener-issue14-login");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dir.resolve("login.sqlite"));
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void anUnconfiguredServerSaysSignInIsUnavailableInsteadOfADeadLink() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/login")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("Sign-in is unavailable: this server has no GitHub OAuth app configured.");
        assertThat(response.getContentAsString()).doesNotContain("oauth2/authorization/github");
    }
}
