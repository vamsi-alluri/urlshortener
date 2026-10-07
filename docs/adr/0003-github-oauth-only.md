# GitHub OAuth is the only sign-in method

Users sign in exclusively with GitHub OAuth; there is no email/password flow and no other provider. For an API-first service aimed at developers this deletes the entire password and SMTP subsystem, and GitHub supplies a verified contact email for moderation. The deliberate cost is excluding non-GitHub developers; additional providers can be added later without migrating accounts.
