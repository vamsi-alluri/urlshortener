package com.urlshortener.user;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A person authenticated via GitHub OAuth; the owner of the Short Links they create
 * (GLOSSARY: User).
 *
 * <p>One row per GitHub identity: GitHub's numeric id is the stable unique identity, because a
 * GitHub login can be renamed. There is deliberately no password column and no
 * email-verification state (ADR-0003); the email is kept only as the Moderation contact
 * address.
 *
 * <p>{@code createdAt} is persisted as ISO-8601 UTC text, not as a JDBC timestamp: the SQLite
 * driver stores a {@code Timestamp} parameter as epoch-millis text, which cannot be read back
 * into an {@code Instant}. See {@code V2__users.sql}.
 */
@Table("users")
public class User {

    @Id
    private Long id;

    @Column("github_id")
    private long githubId;

    private String login;

    private String name;

    private String email;

    @Column("created_at")
    private String createdAt;

    public User() {
    }

    static User fromGitHub(long githubId, String login, String name, String email, Instant createdAt) {
        User user = new User();
        user.githubId = githubId;
        user.login = login;
        user.name = name;
        user.email = email;
        user.createdAt = createdAt.toString();
        return user;
    }

    /** Refreshes the mutable GitHub profile fields on a repeat sign-in. */
    void updateProfile(String login, String name, String email) {
        this.login = login;
        this.name = name;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public long getGithubId() {
        return githubId;
    }

    public String getLogin() {
        return login;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return Instant.parse(createdAt);
    }
}
