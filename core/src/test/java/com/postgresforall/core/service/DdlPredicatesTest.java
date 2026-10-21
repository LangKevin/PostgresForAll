package com.postgresforall.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlPredicatesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "SELECT 1;|false",
            "CREATE TABLE foo (id int);|false",
            "INSERT INTO foo VALUES (1);|false",
            "BEGIN; UPDATE foo SET id=2; COMMIT;|false",
            "CREATE DATABASE bar;|true",
            "DROP DATABASE bar;|true",
            "ALTER DATABASE bar OWNER TO scheduleme;|true",
            "CREATE TABLESPACE ts LOCATION '/tmp';|true",
            "VACUUM;|true",
            "VACUUM (VERBOSE, ANALYZE);|true",
            "REINDEX DATABASE foo;|true",
            "CREATE INDEX CONCURRENTLY idx ON foo(id);|true"})
    void containsNonTransactional_flagsTheRightStatements(String sql, boolean expected) {
        assertThat(DdlPredicates.containsNonTransactionalStatements(sql)).isEqualTo(expected);
    }

    @Test
    void containsNonTransactional_ignoresCommentedOut() {
        assertThat(DdlPredicates.containsNonTransactionalStatements("-- CREATE DATABASE foo;\n-- VACUUM;\nSELECT 1;"))
                .isFalse();
    }

    @Test
    void containsNonTransactional_ignoresBlockComments() {
        assertThat(DdlPredicates.containsNonTransactionalStatements("/* CREATE DATABASE x; */ SELECT 1;")).isFalse();
    }

    @Test
    void indexOfCreateDatabase_findsFirstStatement() {
        assertThat(DdlPredicates.indexOfCreateDatabaseStatement(List.of(
                "SELECT 1", "CREATE DATABASE foo OWNER scheduleme", "CREATE TABLE bar(id int)"))).isEqualTo(1);
    }

    @Test
    void indexOfCreateDatabase_returnsMinusOneWhenAbsent() {
        assertThat(DdlPredicates.indexOfCreateDatabaseStatement(List.of("SELECT 1", "CREATE TABLE bar(id int)")))
                .isEqualTo(-1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "CREATE DATABASE foo|foo",
            "CREATE DATABASE \"foo\"|foo",
            "CREATE DATABASE IF NOT EXISTS bar OWNER baz|bar",
            "create database  Mixed_Case_DB_2|Mixed_Case_DB_2",
            "CREATE DATABASE foo WITH OWNER = scheduleme ENCODING = 'UTF8'|foo"})
    void extractCreatedDatabaseName_parses(String stmt, String expected) {
        assertThat(DdlPredicates.extractCreatedDatabaseName(stmt)).isEqualTo(expected);
    }

    @Test
    void extractCreatedDatabaseName_returnsNullForMalformed() {
        assertThat(DdlPredicates.extractCreatedDatabaseName("CREATE TABLE foo(id int)")).isNull();
    }
}
