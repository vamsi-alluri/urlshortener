package com.urlshortener.user;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The signed-in User as Spring Security sees it: the app-scoped {@link #getUserId() users.id},
 * the GitHub identity, and the raw GitHub profile attributes. Serializable because it lives in
 * the HTTP session, which Spring Session persists into SQLite (issue #2).
 *
 * <p>{@link #getName()} is the numeric GitHub identity (the OAuth2 user name attribute), so the
 * principal name survives a GitHub login rename.
 */
public final class GitHubPrincipalUser implements OAuth2User, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String ROLE_USER = "ROLE_USER";

    private final long userId;

    private final long githubId;

    private final String login;

    private final Map<String, Object> attributes;

    public GitHubPrincipalUser(long userId, long githubId, String login, Map<String, Object> attributes) {
        this.userId = userId;
        this.githubId = githubId;
        this.login = login;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    static GitHubPrincipalUser of(User user, Map<String, Object> githubAttributes) {
        return new GitHubPrincipalUser(user.getId(), user.getGithubId(), user.getLogin(), githubAttributes);
    }

    @Override
    public String getName() {
        return String.valueOf(githubId);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Set.of(new SimpleGrantedAuthority(ROLE_USER));
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /** The app-scoped identity: the {@code users.id} of the signed-in User. */
    public long getUserId() {
        return userId;
    }

    public long getGithubId() {
        return githubId;
    }

    public String getLogin() {
        return login;
    }
}
