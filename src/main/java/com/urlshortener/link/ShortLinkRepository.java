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

    private static final String INCREMENT_CLICK_COUNT = """
            UPDATE links
            SET click_count = click_count + 1
            WHERE slug = ? AND deactivated_at IS NULL
            """;

    private static final String SELECT_PAGE_BY_OWNER = """
            SELECT slug, destination, owner, click_count, created_at, deactivated_at
            FROM links
            WHERE owner = ?
            ORDER BY created_at DESC, slug ASC
            LIMIT ? OFFSET ?
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
     * Records one Click on the live Short Link bound to the Slug (D3): the counter
     * column is incremented in place by a single {@code UPDATE}, so the increment
     * is atomic and concurrent follows each land a full one. Only a live row
     * counts — a Deactivated row is skipped, and an unknown Slug matches nothing
     * — so 404s and 410s never count.
     */
    void incrementClickCount(String slug) {
        jdbc.update(INCREMENT_CLICK_COUNT, slug);
    }

    /**
     * One page of a User's Short Links, newest first — every row they own, live
     * or (once #6 lands) Deactivated, for the owner's list (D12). The {@code owner}
     * column stores the creating User's {@code users.id} as text (the #4
     * integration note); the bind parameter is that id as a string, so the
     * comparison stays text-to-text with no CAST.
     */
    List<ShortLink> pageByOwner(String owner, int limit, int offset) {
        return jdbc.query(SELECT_PAGE_BY_OWNER, SHORT_LINK, owner, limit, offset);
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
