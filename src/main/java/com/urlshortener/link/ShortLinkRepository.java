package com.urlshortener.link;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stores Short Links in the {@code links} table (V1__links.sql, ADR-0005).
 *
 * SQLite's JDBC driver reports no SQLState and Spring has no SQLite
 * error-code table, so a taken Slug is detected from the driver's constraint
 * result codes rather than from a translated {@code DuplicateKeyException}.
 */
@Component
class ShortLinkRepository {

    private static final String INSERT = """
            INSERT INTO links (slug, destination, owner, click_count, created_at, deactivated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_LIVE_BY_SLUG = """
            SELECT slug, destination, owner, click_count, created_at, deactivated_at
            FROM links
            WHERE slug = ? AND deactivated_at IS NULL
            """;

    private static final String SELECT_BY_SLUG = """
            SELECT slug, destination, owner, click_count, created_at, deactivated_at
            FROM links
            WHERE slug = ?
            """;

    private static final String DEACTIVATE_LIVE_BY_SLUG_AND_OWNER = """
            UPDATE links
            SET deactivated_at = ?
            WHERE slug = ? AND owner = ? AND deactivated_at IS NULL
            """;

    private static final RowMapper<ShortLink> SHORT_LINK = ShortLinkRepository::toShortLink;

    private final JdbcTemplate jdbc;

    ShortLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists a new Short Link. Returns {@code false} when the Slug is already
     * taken by any existing row — live or Deactivated — so the caller draws a
     * fresh Slug; a taken Slug is never reused (ADR-0004).
     */
    boolean insert(ShortLink link) {
        try {
            jdbc.update(INSERT,
                    link.slug(),
                    link.destination(),
                    link.owner(),
                    link.clickCount(),
                    link.createdAt().toString(),
                    link.deactivatedAt() == null ? null : link.deactivatedAt().toString());
        } catch (DataAccessException failure) {
            if (slugAlreadyTaken(failure)) {
                return false;
            }
            throw failure;
        }
        return true;
    }

    /**
     * Finds the live Short Link bound to a Slug. A Deactivated Short Link does
     * not resolve (GLOSSARY); Deactivation itself arrives with ticket #6.
     */
    Optional<ShortLink> findLiveBySlug(String slug) {
        List<ShortLink> matches = jdbc.query(SELECT_LIVE_BY_SLUG, SHORT_LINK, slug);
        return matches.stream().findFirst();
    }

    /**
     * Finds the Short Link bound to a Slug — Deactivated rows included, unlike
     * {@link #findLiveBySlug}: deactivation (issue #6) reads the row to answer
     * 404, 409, or 410 on a Slug that no longer resolves.
     */
    Optional<ShortLink> findBySlug(String slug) {
        List<ShortLink> matches = jdbc.query(SELECT_BY_SLUG, SHORT_LINK, slug);
        return matches.stream().findFirst();
    }

    /**
     * Marks the owner's live Short Link Deactivated — the one lifecycle change
     * a Short Link has (ADR-0002) — and returns {@code true}. Returns
     * {@code false}, touching nothing, when the Slug is unknown, belongs to
     * another User, or is already Deactivated. The row always stays, so the
     * Slug is never reissued (ADR-0004). The timestamp is ISO-8601 UTC text,
     * the {@code deactivated_at} column's storage format since V1.
     */
    boolean deactivate(String slug, String owner, Instant deactivatedAt) {
        return jdbc.update(DEACTIVATE_LIVE_BY_SLUG_AND_OWNER,
                deactivatedAt.toString(), slug, owner) > 0;
    }

    private static ShortLink toShortLink(ResultSet rs, int rowNum) throws SQLException {
        String deactivatedAt = rs.getString("deactivated_at");
        return new ShortLink(
                rs.getString("slug"),
                rs.getString("destination"),
                rs.getString("owner"),
                rs.getLong("click_count"),
                Instant.parse(rs.getString("created_at")),
                deactivatedAt == null ? null : Instant.parse(deactivatedAt));
    }

    private static boolean slugAlreadyTaken(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLiteException sqlite
                    && (sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY
                        || sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)) {
                return true;
            }
        }
        return false;
    }
}
