package com.postgresforall.core.service;

import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.testsupport.StubRunner;
import com.postgresforall.core.util.CancelToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class PgClientToolsTest {

    /** Fake file system: a set of existing files, a PATH and install roots. */
    private static final class FakeSearch extends PgToolSearch {
        final Set<String> files = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> path = new ArrayList<>();
        List<String> roots = new ArrayList<>();

        @Override
        public boolean fileExists(String p) {
            return files.contains(p);
        }

        @Override
        public List<String> pathEntries() {
            return path;
        }

        @Override
        public List<String> installRoots() {
            return roots;
        }

        void addTools(String dir) {
            files.add(Path.of(dir, PgClientTools.exeName("pg_dump")).toString());
            files.add(Path.of(dir, PgClientTools.exeName("pg_restore")).toString());
        }
    }

    /** Answers "pg_dump --version" with a version keyed by directory. */
    private static StubRunner versionRunner(Map<String, String> versionsByDir) {
        return new StubRunner((file, args) -> {
            var dir = Path.of(file).getParent().toString();
            var v = versionsByDir.get(dir);
            return v != null
                    ? new ProcessResult(0, "pg_dump (PostgreSQL) " + v + "\n", "")
                    : new ProcessResult(1, "", "not found");
        });
    }

    @Test
    void locate_picksNewestInstalledVersion() {
        var search = new FakeSearch();
        search.roots = List.of("C:\\PG\\16\\bin", "C:\\PG\\18\\bin", "C:\\PG\\17\\bin");
        search.roots.forEach(search::addTools);
        var runner = versionRunner(Map.of("C:\\PG\\16\\bin", "16.4", "C:\\PG\\17\\bin", "17.2", "C:\\PG\\18\\bin", "18.1"));

        var tools = PgClientTools.locate(null, runner, search, CancelToken.NONE);

        assertThat(tools).isNotNull();
        assertThat(tools.majorVersion()).isEqualTo(18);
        assertThat(tools.binDirectory()).isEqualTo("C:\\PG\\18\\bin");
    }

    @Test
    void locate_configuredPathWins_evenIfOlder() {
        var search = new FakeSearch();
        search.roots = List.of("C:\\PG\\18\\bin");
        search.addTools("C:\\PG\\18\\bin");
        search.addTools("D:\\tools\\pg16");
        var runner = versionRunner(Map.of("C:\\PG\\18\\bin", "18.1", "D:\\tools\\pg16", "16.4"));

        var tools = PgClientTools.locate("D:\\tools\\pg16", runner, search, CancelToken.NONE);

        assertThat(tools.binDirectory()).isEqualTo("D:\\tools\\pg16");
        assertThat(tools.majorVersion()).isEqualTo(16);
    }

    @Test
    void locate_searchesPath_andSkipsDirsMissingPgRestore() {
        var search = new FakeSearch();
        search.path = List.of("C:\\nothing", "C:\\half", "C:\\pgbin");
        search.files.add(Path.of("C:\\half", PgClientTools.exeName("pg_dump")).toString());
        search.addTools("C:\\pgbin");
        var runner = versionRunner(Map.of("C:\\half", "19.0", "C:\\pgbin", "17.0"));

        var tools = PgClientTools.locate(null, runner, search, CancelToken.NONE);

        assertThat(tools.binDirectory()).isEqualTo("C:\\pgbin");
    }

    @Test
    void locate_returnsNull_whenNothingInstalled() {
        assertThat(PgClientTools.locate(null, versionRunner(Map.of()), new FakeSearch(), CancelToken.NONE)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "pg_dump (PostgreSQL) 18.1|18",
            "16.4 (Debian 16.4-1.pgdg120+2)|16",
            "18.1|18",
            "PostgreSQL 9.6.24 on x86_64|9",
            "''|",
            "no digits|"})
    void parseMajorVersion_handlesCommonFormats(String text, Integer expected) {
        assertThat(PgClientTools.parseMajorVersion(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"18,16,true", "18,18,true", "16,18,false"})
    void supportsServer_requiresToolsAtLeastServerVersion(int tools, int server, boolean expected) {
        assertThat(new PgClientTools("x", tools).supportsServer(server)).isEqualTo(expected);
    }
}
