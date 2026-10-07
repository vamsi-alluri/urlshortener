package com.urlshortener.user;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User, Long> {

    Optional<User> findByGithubId(long githubId);

    long countByGithubId(long githubId);
}
