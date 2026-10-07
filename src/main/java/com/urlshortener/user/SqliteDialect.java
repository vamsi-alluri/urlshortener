package com.urlshortener.user;

import org.springframework.data.relational.core.dialect.AnsiDialect;
import org.springframework.data.relational.core.dialect.LimitClause;

/**
 * A SQLite dialect for Spring Data JDBC, which ships none of its own. Everything stays ANSI
 * except the limit clause: SQLite understands {@code LIMIT n} / {@code OFFSET n} / {@code
 * LIMIT n OFFSET m} (see https://www.sqlite.org/lang_select.html), not the {@code FETCH FIRST
 * n ROWS ONLY} family that the built-in ANSI and H2 dialects render.
 */
final class SqliteDialect extends AnsiDialect {

    static final SqliteDialect INSTANCE = new SqliteDialect();

    private static final LimitClause LIMIT_CLAUSE = new LimitClause() {

        @Override
        public String getLimit(long limit) {
            return "LIMIT " + limit;
        }

        @Override
        public String getOffset(long offset) {
            return "OFFSET " + offset;
        }

        @Override
        public String getLimitOffset(long limit, long offset) {
            return "LIMIT " + limit + " OFFSET " + offset;
        }

        @Override
        public LimitClause.Position getClausePosition() {
            return LimitClause.Position.AFTER_ORDER_BY;
        }
    };

    private SqliteDialect() {
    }

    @Override
    public LimitClause limit() {
        return LIMIT_CLAUSE;
    }
}
