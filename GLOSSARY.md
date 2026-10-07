# URL Shortener

A public URL shortener: developers sign in with GitHub and create Short Links through an API; anyone can follow them.

## Language

### Short Links

**Short Link**:
A published redirect binding a Slug to a fixed Destination.
_Avoid_: short URL, shortened link, redirect

**Slug**:
The system-assigned identifier in a short link's path — always auto-generated, never chosen by users, and never reissued once used.
_Avoid_: custom slug, vanity slug, short code, key, alias

**Destination**:
The long public web URL (http/https) a Slug resolves to when followed.
_Avoid_: target URL, long link, original URL

**Deactivated**:
A Short Link that no longer resolves, deliberately killed by its owner or by moderation; one-way, and its Slug is never reissued.
_Avoid_: deleted, expired, removed

**Click**:
A single follow of a Short Link by a visitor; counted as a running total per link for its owner.
_Avoid_: visit, hit, view

### Access

**User**:
A person authenticated via GitHub OAuth; the owner of the Short Links they create.
_Avoid_: account, member, subscriber

**API Key**:
A User's single regenerable secret for creating Short Links; regenerating it instantly revokes the previous key.
_Avoid_: token, secret, password

**Moderation**:
The service operator's authority to Deactivate a Short Link for abuse, exercised on confirmed reports.
_Avoid_: admin, takedown
