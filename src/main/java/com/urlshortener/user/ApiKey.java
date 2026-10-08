package com.urlshortener.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The stored form of an API Key (GLOSSARY): the SHA-256 hash of the plaintext key, the
 * keyholder's {@code users.id}, and the issuance instant. The plaintext is never stored
 * (D8) — it exists only in the response that issued the key, which shows it exactly once.
 *
 * <p>Exactly one row per User, ever ({@code UNIQUE (user_id)}): regeneration does not add a
 * row, it replaces this row's hash, which revokes the previous key instantly.
 *
 * <p>{@code createdAt} is persisted as ISO-8601 UTC text, not as a JDBC timestamp (the
 * xerial quirk — see {@code V2__users.sql}).
 */
@Table("api_keys")
public class ApiKey {

    @Id
    private Long id;

    @Column("user_id")
    private long userId;

    @Column("key_hash")
    private String keyHash;

    @Column("created_at")
    private String createdAt;

    public ApiKey() {
    }

    /** The first issuance of a User's key: stores only the hash of the plaintext. */
    static ApiKey issuedFor(long userId, String plaintextKey, Instant createdAt) {
        ApiKey apiKey = new ApiKey();
        apiKey.userId = userId;
        apiKey.keyHash = hashOf(plaintextKey);
        apiKey.createdAt = createdAt.toString();
        return apiKey;
    }

    /**
     * Regeneration: the row now holds the new key's hash, so the previous key matches
     * nothing from the moment this is saved.
     */
    void rotatedTo(String plaintextKey, Instant createdAt) {
        this.keyHash = hashOf(plaintextKey);
        this.createdAt = createdAt.toString();
    }

    /**
     * SHA-256 hex of a plaintext key — the only form at rest and the only form compared
     * (D8). Deliberately a fast hash: slow password-hashes defend low-entropy secrets from
     * offline brute force, and a random 32-character base62 key (~190 bits) is not one.
     */
    static String hashOf(String plaintextKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(plaintextKey.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (NoSuchAlgorithmException exception) {
            // unreachable: the JDK spec guarantees SHA-256
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public Instant getCreatedAt() {
        return Instant.parse(createdAt);
    }
}
