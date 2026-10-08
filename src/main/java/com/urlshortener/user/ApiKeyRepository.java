package com.urlshortener.user;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface ApiKeyRepository extends CrudRepository<ApiKey, Long> {

    /** The User's one key row — exactly one, ever ({@code UNIQUE (user_id)}). */
    Optional<ApiKey> findByUserId(long userId);

    /** The row whose stored hash matches a presented key's hash — the authentication lookup. */
    Optional<ApiKey> findByKeyHash(String keyHash);
}
