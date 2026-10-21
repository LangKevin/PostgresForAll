package com.postgresforall.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptSplitterTest {

    @Test
    void splits_simpleStatements() {
        assertThat(ScriptSplitter.split("SELECT 1; SELECT 2;")).containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    void ignores_semicolonInsideStringLiteral() {
        var parts = ScriptSplitter.split("SELECT 'a;b'; SELECT 2;");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("'a;b'");
    }

    @Test
    void ignores_semicolonInsideDollarQuote() {
        var parts = ScriptSplitter.split("DO $$ BEGIN PERFORM 1; PERFORM 2; END $$; SELECT 3;");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("PERFORM 2");
        assertThat(parts.get(1)).isEqualTo("SELECT 3");
    }

    @Test
    void ignores_semicolonInLineComment() {
        assertThat(ScriptSplitter.split("SELECT 1; -- end of line ; still comment\nSELECT 2;")).hasSize(2);
    }

    // ---- Java-port edge cases ----------------------------------------------

    @Test
    void taggedDollarQuote_withInnerUntaggedDollars() {
        var parts = ScriptSplitter.split(
                "CREATE FUNCTION f() RETURNS text AS $fn$ SELECT '$$;' ; $fn$ LANGUAGE sql; SELECT 1");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).endsWith("LANGUAGE sql");
        assertThat(parts.get(1)).isEqualTo("SELECT 1");
    }

    @Test
    void nestedBlockComments_hideSemicolons() {
        var parts = ScriptSplitter.split("/* outer /* inner; */ still; */ SELECT 1; SELECT 2");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).endsWith("SELECT 1");
        assertThat(parts.get(1)).isEqualTo("SELECT 2");
    }

    @Test
    void doubledQuotesInString_andQuotedIdentifiers() {
        var parts = ScriptSplitter.split("SELECT 'it''s; fine' AS \"a;b\"; SELECT 2");
        assertThat(parts).containsExactly("SELECT 'it''s; fine' AS \"a;b\"", "SELECT 2");
    }

    @Test
    void unterminatedDollarQuote_keepsRestAsOneStatement() {
        assertThat(ScriptSplitter.split("DO $$ BEGIN; END; SELECT 1")).hasSize(1);
    }

    @Test
    void emptyAndWhitespaceStatements_areDropped() {
        assertThat(ScriptSplitter.split(" ; ;\n;SELECT 1;;  ")).containsExactly("SELECT 1");
        assertThat(ScriptSplitter.split("")).isEmpty();
    }
}
