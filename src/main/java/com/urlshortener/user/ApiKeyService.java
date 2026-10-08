package com.urlshortener.user;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * The single API Key per User (issue #3): generated once, shown once, regenerable — and
 * regenerating replaces the stored hash, which revokes the previous key the instant the
 * row is saved.
 *
 * <p>The plaintext key exists in the clear only in the return value of {@link #issueFor}
 * and the response that shows it once; everything else — the store, the authentication
 * path — sees only the SHA-256 hash (D8).
 */
@Service
final class ApiKeyService {

    private final ApiKeyRepository apiKeys;

    private final UserRepository users;

    private final ApiKeyGenerator generator;

    ApiKeyService(ApiKeyRepository apiKeys, UserRepository users, ApiKeyGenerator generator) {
        this.apiKeys = apiKeys;
        this.users = users;
        this.generator = generator;
    }

    /** Whether the User holds their key — the key page issues only on the first visit. */
    boolean hasKeyFor(long userId) {
        return apiKeys.findByUserId(userId).isPresent();
    }

    /**
     * Issues the User's key — first issuance and every regeneration alike — and returns the
     * plaintext for its one showing. Any previous key stops working the moment this
     * returns: its hash is gone from the row.
     */
    String issueFor(long userId) {
        String plaintextKey = generator.generate();
        Instant createdAt = Instant.now();
        ApiKey apiKey = apiKeys.findByUserId(userId).orElse(null);
        if (apiKey == null) {
            apiKeys.save(ApiKey.issuedFor(userId, plaintextKey, createdAt));
        }
        else {
            apiKey.rotatedTo(plaintextKey, createdAt);
            apiKeys.save(apiKey);
        }
        return plaintextKey;
    }

    /**
     * Authenticates a presented Bearer credential (D9): the presented key's hash must match
     * the stored hash of the User's one key. A malformed, unknown, or regenerated-away key
     * matches nothing.
     */
    Optional<KeyMatch> authenticate(String presentedKey) {
        if (!ApiKeyGenerator.isWellFormed(presentedKey)) {
            return Optional.empty();
        }
        return apiKeys.findByKeyHash(ApiKey.hashOf(presentedKey)).flatMap(this::keyholderOf);
    }

    private Optional<KeyMatch> keyholderOf(ApiKey apiKey) {
        return users.findById(apiKey.getUserId()).map(user -> new KeyMatch(user, apiKey));
    }

    /** A presented key that matched the stored hash: the keyholder and the matched row. */
    record KeyMatch(User user, ApiKey apiKey) {
    }
}
