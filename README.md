# urlshortener

A public URL shortener: developers sign in with GitHub and create Short Links through an API; anyone can follow them.

- The plan and the specs live in [GitHub Issues](https://github.com/vamsi-alluri/urlshortener/issues) (#1–#9, dependency-ordered)
- Vocabulary: `GLOSSARY.md`
- Decisions: `docs/adr/`
- Agent conventions: `AGENTS.md` and `docs/agents/`

## API

Live base URL: `https://short.vamsi-alluri.me` (local: `http://localhost:8080`; on the tailnet: `http://100.93.117.80:8080`).

All errors are RFC 7807 `application/problem+json` (`{"type","title","status","detail"}`).

### Create a Short Link — `POST /api/links`

Unauthenticated in the current version (API-key auth arrives with #4); the route is CSRF-exempt.

```
curl -X POST https://short.vamsi-alluri.me/api/links \
  -H "Content-Type: application/json" \
  -d '{"destination": "https://example.com/a/very/long/url"}'
```

`201 Created`:

```json
{"slug": "aB3xK9z", "short_url": "https://short.vamsi-alluri.me/aB3xK9z", "destination": "https://example.com/a/very/long/url"}
```

Errors: `400` when the Destination is not an absolute http/https URL (full validation rules land with #4).

The Slug is always system-generated (never user-chosen — ADR-0004): random, 7 characters, mixed-case base62, case-sensitive. Every creation makes a new Short Link, even for a Destination that already exists.

### Follow a Short Link — `GET /{slug}`

- Live Short Link → `302 Found` with `Location: <destination>` (never a 301 — ADR-0001)
- Unknown Slug → `404` problem+json
- Deactivated Short Link → `410 Gone` (lands with #6)

### Sign in / out — `GET /login` · `POST /logout`

"Sign in with GitHub" only (ADR-0003). Requires the server's OAuth app credentials (see Running below); unconfigured, the app boots and serves the API but sign-in is unavailable.

### Who am I — `GET /me`

Session or API key (`Authorization: Bearer <key>`): `{"id","githubId","login"}`. Otherwise `401` problem+json — including for a malformed, unknown, or revoked key.

### Your API key — `GET /me/key` · `POST /me/key`

One key per User, ever: `ush_` + 32 random base62 characters (~190 bits). Issued on your first visit to the key page (session required) and shown exactly once — it is stored as a SHA-256 hash and cannot be displayed again. `POST /me/key` (the Regenerate button, session-authenticated) revokes the previous key the instant it is pressed and shows the new one exactly once. Use it as `Authorization: Bearer <key>` on `GET /me` today; authenticated creation arrives with #4.

### Coming on the board

#4 authenticated creation + destination validation · #5 Click counts + your links · #6 deactivation → 410 · #7 rate limits per key · #9 the real deployment.

## Running

- Database: SQLite file at `URLSHORTENER_DB_URL` (default `jdbc:sqlite:./data/urlshortener.db`).
- Public base URL: `URLSHORTENER_BASE_URL` (default `http://localhost:8080`).
- GitHub OAuth app credentials (create at <https://github.com/settings/developers> with authorization callback URL `<base-url>/login/oauth2/code/github`), supplied as environment variables (standard Spring relaxed binding):
  - `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID`
  - `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET`

Sign-in is GitHub-only (ADR-0003): no passwords are stored and no email infrastructure exists. Sessions live in the same SQLite database, so restarts and redeploys do not log anyone out.
