package dev.supavolt.api.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.supavolt.api.features.sqleditor.SqlStatementSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlStatementSplitterTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "select 1",
        "select 1;",
        "select ';'",
        "select 'it''s; fine'",
        "select 1; -- trailing comment",
        "select 1; -- trailing; comment with semicolons",
        "select 1 /* ; */",
        "select \"weird;name\" from t",
        "create function f() returns int as $$ select 1; select 2; $$ language sql",
        "create function f() returns int as $body$ begin; return 1; end; $body$ language plpgsql",
        "select $1::int, $2::int",
        "select a$b from t; "
    })
    void counts_one_statement(String sql) {
        assertThat(SqlStatementSplitter.split(sql)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "select 1; select 2",
        "select ';'; select 2",
        "select 1 /* ; */; drop table x",
        "select $1; drop table x",
        "select $1, $2; drop table x",   // "$1, $" is not a dollar-quote tag
        "select 'a$b'; select $x$ ; $x$"
    })
    void counts_two_statements(String sql) {
        assertThat(SqlStatementSplitter.split(sql)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ";;", "-- only a comment"})
    void counts_no_statements(String sql) {
        assertThat(SqlStatementSplitter.split(sql)).isEmpty();
    }

    @Test
    void trims_each_statement() {
        assertThat(SqlStatementSplitter.split("  select 1 ;  select 2  ")).containsExactly("select 1", "select 2");
    }
}
