-- Short Links (ADR-0005: SQLite is the initial store).
--
-- WAL mode is NOT set here: Flyway runs each migration inside a transaction,
-- and SQLite refuses to change journal_mode within one. WAL is enabled per
-- connection via spring.datasource.hikari.connection-init-sql in
-- application.yml instead.
CREATE TABLE links (
    slug           TEXT PRIMARY KEY,            -- case-sensitive, never reissued (ADR-0004)
    destination    TEXT NOT NULL,               -- fixed at creation (ADR-0002)
    owner          TEXT,                        -- null until ticket #4 records the creating User
    click_count    INTEGER NOT NULL DEFAULT 0,  -- incremented on follow by ticket #5
    created_at     TEXT NOT NULL,               -- ISO-8601 instant
    deactivated_at TEXT                         -- null while live; ticket #6 Deactivates
);
