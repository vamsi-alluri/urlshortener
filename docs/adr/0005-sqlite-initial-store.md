# SQLite as the initial store

The service stores Short Links, Users, API Keys, and Click counts in a single SQLite database (WAL mode), not Postgres. For a single-node deployment this is zero-ops, trivially backed up, and handles the redirect hot path with room to spare. The ceiling is known and accepted: one writer, one node — if the service ever needs multi-node hosting, migrating to Postgres is a schema port and a data migration, and that is the price of starting simple.
