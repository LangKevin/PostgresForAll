package com.postgresforall.core.service;

import com.postgresforall.core.model.OperationEvent.Severity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.text.DecimalFormatSymbols;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseOperationsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "pg_dump: dumping database \"scheduleme\"|INFO",
            "pg_dump: dumping contents of table \"public.users\"|INFO",
            "pg_dump: WARNING: column has type X|WARN",
            "HINT: try this instead|WARN",
            "pg_dump: error: connection failed|ERROR",
            "FATAL: role does not exist|ERROR",
            "PANIC: invalid record length|ERROR"})
    void classifyPgLine_scoresKnownPatterns(String line, Severity expected) {
        assertThat(DatabaseOperations.classifyPgLine(line)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "simple|\"simple\"",
            "with\"quote|\"with\"\"quote\"",
            "public.users|\"public.users\""})
    void quoteIdentifier_doublesEmbeddedQuotes(String ident, String expected) {
        assertThat(DatabaseOperations.quoteIdentifier(ident)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|0 B", "512|512 B", "2048|2 KB", "1572864|1.5 MB", "3221225472|3 GB"})
    void formatBytes_rendersHumanReadable(long bytes, String expected) {
        // DecimalFormat follows the default locale's decimal separator.
        var sep = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        assertThat(DatabaseOperations.formatBytes(bytes)).isEqualTo(expected.replace('.', sep));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "pg_restore: error: could not execute query: ERROR:  unrecognized configuration parameter \"transaction_timeout\"|true",
            "pg_restore: error: could not execute query: ERROR:  role \"bob\" does not exist|false",
            "pg_restore: error: could not execute query: ERROR:  unrecognized configuration parameter \"foo\"|false"})
    void isBenignRestoreError_onlyForgivesTransactionTimeout(String line, boolean expected) {
        assertThat(DatabaseOperations.isBenignRestoreError(line)).isEqualTo(expected);
    }
}
