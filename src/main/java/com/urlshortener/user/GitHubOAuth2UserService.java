package com.urlshortener.user;

import java.time.Instant;
import java.util.Map;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Completes the GitHub authorization-code flow: fetches the GitHub profile at the user-info
 * endpoint, then creates the User row on first sign-in and reuses (and refreshes) it on every
 * repeat sign-in, so a User's links stay theirs (issue #2).
 */
@Service
final class GitHubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final UserRepository users;

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();

    GitHubOAuth2UserService(UserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User github = delegate.loadUser(request);
        Map<String, Object> attributes = github.getAttributes();
        String userNameAttribute = request.getClientRegistration().getProviderDetails().getUserInfoEndpoint()
                .getUserNameAttributeName();
        long githubId = requireGithubId(attributes, userNameAttribute);
        String login = requireLogin(attributes);
        String name = textOrNull(attributes.get("name"));
        String email = textOrNull(attributes.get("email"));
        User user = upsert(githubId, login, name, email);
        return GitHubPrincipalUser.of(user, attributes);
    }

    private User upsert(long githubId, String login, String name, String email) {
        User user = users.findByGithubId(githubId)
                .orElseGet(() -> User.fromGitHub(githubId, login, name, email, Instant.now()));
        user.updateProfile(login, name, email);
        return users.save(user);
    }

    private static long requireGithubId(Map<String, Object> attributes, String userNameAttribute) {
        Object value = attributes.get(userNameAttribute);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            }
            catch (NumberFormatException ignored) {
                // fall through to the error below
            }
        }
        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info",
                "GitHub identity ('" + userNameAttribute + "') is missing or not numeric in the user info response",
                null));
    }

    private static String requireLogin(Map<String, Object> attributes) {
        Object value = attributes.get("login");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new OAuth2AuthenticationException(
                new OAuth2Error("invalid_user_info", "GitHub login is missing in the user info response", null));
    }

    private static String textOrNull(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
