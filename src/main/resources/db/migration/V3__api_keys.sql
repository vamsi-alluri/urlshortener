-- Ticket #3 — API Keys (issue #3).
-- Exactly one key per User, ever: UNIQUE (user_id) enforces the single-row shape, and
-- regeneration replaces this row's hash — the previous key stops matching the moment
-- the row is replaced, which is the instant revocation of the GLOSSARY's API Key.
-- key_hash is the SHA-256 hex of the plaintext key (D8): the key carries ~190 random bits
-- (ush_ + 32 base62 characters, D7), so offline brute force is infeasible against a fast
-- hash — slow password-hashes defend low-entropy secrets, which this is not.
-- created_at is ISO-8601 UTC text written by the application (the xerial Timestamp quirk
-- that V2__users.sql documents).
CREATE TABLE api_keys (
    id         INTEGER PRIMARY KEY,
    user_id    INTEGER NOT NULL,
    key_hash   TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    CONSTRAINT api_keys_user_id_unique UNIQUE (user_id)
);
