-- Ticket #2 — Users (issue #2, ADR-0003).
-- One row per GitHub identity: GitHub's numeric id is the stable unique identity, because a
-- GitHub login can be renamed. There is deliberately no password column and no
-- email-verification state (ADR-0003); the email is kept only as the Moderation contact
-- address.
-- created_at is ISO-8601 UTC text written by the application: the SQLite driver stores a
-- Timestamp parameter as epoch-millis text, which cannot be read back into an Instant.
CREATE TABLE users (
    id         INTEGER PRIMARY KEY,
    github_id  INTEGER NOT NULL,
    login      TEXT    NOT NULL,
    name       TEXT,
    email      TEXT,
    created_at TEXT    NOT NULL,
    CONSTRAINT users_github_id_unique UNIQUE (github_id)
);
