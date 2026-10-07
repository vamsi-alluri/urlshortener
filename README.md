# urlshortener

A public URL shortener: developers sign in with GitHub and create Short Links through an API; anyone can follow them.

- The plan and the specs live in [GitHub Issues](https://github.com/vamsi-alluri/urlshortener/issues) (#1–#9, dependency-ordered)
- Vocabulary: `GLOSSARY.md`
- Decisions: `docs/adr/`
- Agent conventions: `AGENTS.md` and `docs/agents/`

## Running

- Database: SQLite file at `URLSHORTENER_DB_URL` (default `jdbc:sqlite:./data/urlshortener.db`).
- Public base URL: `URLSHORTENER_BASE_URL` (default `http://localhost:8080`).
- GitHub OAuth app credentials (create at <https://github.com/settings/developers> with authorization callback URL `<base-url>/login/oauth2/code/github`), supplied as environment variables (standard Spring relaxed binding):
  - `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID`
  - `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET`

Sign-in is GitHub-only (ADR-0003): no passwords are stored and no email infrastructure exists. Sessions live in the same SQLite database, so restarts and redeploys do not log anyone out.
