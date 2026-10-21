package com.postgresforall.core.platform;

import com.postgresforall.core.util.CancelToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Lists native PostgreSQL services on this machine. Seam for tests. */
public interface LocalServiceSource {

    List<LocalPgService> list(CancelToken ct);

    /** The source for this OS (empty on Linux, where discovery relies on the port scan). */
    static LocalServiceSource forPlatform(ProcessRunner runner) {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsPostgresServices(runner);
            case MAC -> new MacPostgresServices(runner);
            case LINUX -> ct -> List.of();
        };
    }

    Pattern PORT_LINE = Pattern.compile("^\\s*port\\s*=\\s*'?(\\d+)'?", Pattern.MULTILINE);

    /** The effective {@code port = N} from postgresql.conf (last uncommented one wins); 5432 by default. */
    static int parsePort(String postgresqlConf) {
        var port = 5432;
        if (postgresqlConf == null || postgresqlConf.isEmpty()) return port;
        var m = PORT_LINE.matcher(postgresqlConf);
        while (m.find()) {
            try {
                port = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // keep previous
            }
        }
        return port;
    }

    /** Port configured in {@code dataDir}/postgresql.conf, 5432 when unreadable. */
    static int readPort(String dataDir) {
        if (dataDir == null) return 5432;
        try {
            var conf = Path.of(dataDir, "postgresql.conf");
            return parsePort(Files.isRegularFile(conf) ? Files.readString(conf) : null);
        } catch (IOException | RuntimeException ex) {
            return 5432;
        }
    }
}
