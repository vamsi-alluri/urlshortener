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

Authenticated: the developer's API key (`Authorization: Bearer <key>` — see below). The route is CSRF-exempt, so scripts work with the one credential; every created Short Link is attributed to the keyholder as its owner.

```
curl -X POST https://short.vamsi-alluri.me/api/links \
  -H "Authorization: Bearer ush_..." \
  -H "Content-Type: application/json" \
  -d '{"destination": "https://example.com/a/very/long/url"}'
```

`201 Created`:

```json
{"slug": "aB3xK9z", "short_url": "https://short.vamsi-alluri.me/aB3xK9z", "destination": "https://example.com/a/very/long/url"}
```

Without a valid key: `401` problem+json.

Creation is rate-limited per API key (issue #7): beyond the hourly allowance the next creation answers `429` problem+json with a `Retry-After` header — the whole seconds to wait for the next slot. The allowance refills continuously (default 60 creations per hour, `URLSHORTENER_RATE_LIMIT_CREATIONS_PER_HOUR`); there is no lifetime quota, and nothing but creation is limited — `GET /me`, the future reads on `/api/**`, and the redirect are never throttled.

The Destination must be a public web URL (#4), checked syntactically — no DNS resolution at creation (ADR-0006):

- http or https, absolute, with a host — `javascript:`, `data:`, `ftp:`, `mailto:` are rejected (`invalid_scheme`)
- a public host — `localhost`, `*.localhost`, and private/loopback/link-local IP literals (127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, `::1`, `fc00::/7`, `fe80::/10`) are rejected (`private_destination`)
- at most 2048 characters (`destination_too_long`)

A rejected Destination answers `400` problem+json with every broken rule at once:

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The Destination is not a valid public web URL.","instance":"/api/links","violations":[{"type":"private_destination","detail":"The Destination's host must be public: localhost, *.localhost, and private, loopback, and link-local IP addresses are not allowed."}]}
```

The Slug is always system-generated (never user-chosen — ADR-0004): random, 7 characters, mixed-case base62, case-sensitive. Every creation makes a new Short Link, even for a Destination that already exists.

### Deactivate a Short Link — `DELETE /api/links/{slug}`

Authenticated with the owner's API key, owner-only. One-way, no undo — the Destination stays immutable (ADR-0002) and the row stays with `deactivated_at` set, so the Slug is never reissued (ADR-0004):

```
curl -X DELETE https://short.vamsi-alluri.me/api/links/aB3xK9z \
  -H "Authorization: Bearer ush_..."
```

- `204` on success; following the Short Link then answers a bodyless `410 Gone` that counts no Click
- `404` problem+json when the Slug is unknown or belongs to another User (the two answer identically — no existence leak)
- `409` problem+json when the Short Link is already Deactivated

### Your Short Links — `GET /api/links`

Authenticated (the same API key): one page of your Short Links, newest first — strictly your own; one owner never sees another's. Every entry is `{slug, short_url, destination, click_count, created_at, deactivated}` — `click_count` is the running total of follows (every live follow counts; 404s and 410s never do, and the count freezes at deactivation), and `deactivated` is `false` while the link still resolves, `true` once it is Deactivated (#6).

```
curl "https://short.vamsi-alluri.me/api/links?limit=100&offset=0" \
  -H "Authorization: Bearer ush_..."
```

Pagination: `limit` (default 100; values over 100 clamp to 100) and `offset` (default 0). A non-integer value answers `400` problem+json. Without a valid key: `401` problem+json.

### Follow a Short Link — `GET /{slug}`

- Live Short Link → `302 Found` with `Location: <destination>` (never a 301 — ADR-0001)
- Unknown Slug → `404` problem+json
- Deactivated Short Link → bodyless `410 Gone`

### Sign in / out — `GET /login` · `POST /logout`

"Sign in with GitHub" only (ADR-0003). Requires the server's OAuth app credentials (see Running below); unconfigured, the app boots and serves the API but sign-in is unavailable.

### Who am I — `GET /me`

Session or API key (`Authorization: Bearer <key>`): `{"id","githubId","login"}`. Otherwise `401` problem+json — including for a malformed, unknown, or revoked key.

### Your API key — `GET /me/key` · `POST /me/key`

One key per User, ever: `ush_` + 32 random base62 characters (~190 bits). Issued on your first visit to the key page (session required) and shown exactly once — it is stored as a SHA-256 hash and cannot be displayed again. `POST /me/key` (the Regenerate button, session-authenticated) revokes the previous key the instant it is pressed and shows the new one exactly once. Use it as `Authorization: Bearer <key>` on `GET /me` and to create Short Links at `POST /api/links`.

### Coming on the board

#19 the browser UI · #8 Moderation + abuse contact · #9 the real deployment.

## Running

- Database: SQLite file at `URLSHORTENER_DB_URL` (default `jdbc:sqlite:./data/urlshortener.db`).
- Public base URL: `URLSHORTENER_BASE_URL` (default `http://localhost:8080`).
- Per-key creation allowance: `URLSHORTENER_RATE_LIMIT_CREATIONS_PER_HOUR` (default 60 — issue #7).
- GitHub OAuth app credentials (create at <https://github.com/settings/developers> with authorization callback URL `<base-url>/login/oauth2/code/github`), supplied as environment variables (standard Spring relaxed binding):
  - `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID`
  - `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET`

Sign-in is GitHub-only (ADR-0003): no passwords are stored and no email infrastructure exists. Sessions live in the same SQLite database, so restarts and redeploys do not log anyone out.
