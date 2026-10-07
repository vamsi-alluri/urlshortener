package com.urlshortener.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.dialect.Dialect;

/**
 * Registers {@link SqliteDialect} for Spring Data JDBC. Without a Dialect bean, repository
 * creation fails with "Cannot determine a dialect ... Please provide a Dialect" — Spring Data
 * JDBC does not know ADR-0005's SQLite.
 *
 * <p>NOTE for the merged service: this bean serves the WHOLE application — the {@code users}
 * table of issue #2 and the {@code links} table of issue #1 alike. There must be exactly one
 * Dialect bean in the application.
 */
@Configuration(proxyBeanMethods = false)
class SqliteDataJdbcConfiguration {

    @Bean
    Dialect sqliteDialect() {
        return SqliteDialect.INSTANCE;
    }
}
