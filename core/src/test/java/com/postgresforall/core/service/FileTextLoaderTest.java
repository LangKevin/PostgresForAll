package com.postgresforall.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileTextLoaderTest {
    @TempDir
    Path dir;

    @Test
    void readText_readsExistingFile() throws Exception {
        var f = dir.resolve("a.sql");
        var body = "SELECT 1;\nSELECT 2;\n";
        Files.writeString(f, body);
        assertThat(FileTextLoader.readText(f)).isEqualTo(body);
    }

    @Test
    void readText_stripsUtf8Bom() throws Exception {
        var f = dir.resolve("bom.sql");
        Files.write(f, "\uFEFFSELECT 1;".getBytes(StandardCharsets.UTF_8));
        assertThat(FileTextLoader.readText(f)).isEqualTo("SELECT 1;");
    }

    @Test
    void readText_throwsOnMissingFile() {
        assertThatThrownBy(() -> FileTextLoader.readText(dir.resolve("definitely-missing.sql")))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void readText_rejectsOversizeFiles() throws Exception {
        var f = dir.resolve("big.sql");
        try (var raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(2L * 1024 * 1024); // 2 MiB — exceeds the 1 MiB cap
        }
        assertThatThrownBy(() -> FileTextLoader.readText(f)).isInstanceOf(IllegalStateException.class);
    }

    /** Opt-in: point PFA_SCHEMA_FILE at a large real schema script to exercise the loader on it. */
    @Test
    void readText_loadsScheduleMeSchemaFile_whenPresent() throws Exception {
        var env = System.getenv("PFA_SCHEMA_FILE");
        assumeTrue(env != null && !env.isBlank(), "PFA_SCHEMA_FILE not set");
        var path = Path.of(env);
        assumeTrue(Files.isRegularFile(path), "reference schema file not on this machine");
        var text = FileTextLoader.readText(path);
        assertThat(text).isNotBlank().contains("ScheduleMe");
        assertThat(text.toUpperCase(Locale.ROOT)).contains("CREATE EXTENSION");
    }
}
