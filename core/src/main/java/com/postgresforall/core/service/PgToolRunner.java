package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.PgSslMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles host-side pg_dump / pg_restore invocations that connect to the
 * server over TCP. Pure assembly, unit-testable without running anything.
 *
 * <p>The password goes in PGPASSWORD (never on the command line, where process
 * listings would show it), and --no-password stops the tools from ever blocking
 * on an interactive prompt. Arguments are passed as a list, so paths with spaces
 * need no quoting.
 */
public final class PgToolRunner {
    private PgToolRunner() { }

    /** A ready-to-run client tool invocation. */
    public record Command(String file, List<String> args, Map<String, String> environment) { }

    /** pg_dump --format=custom --verbose --file=&lt;hostFile&gt; for one database. */
    public static Command dump(PgClientTools tools, ConnectionProfile server, String database, String hostFile) {
        var args = connectionArgs(server, database);
        args.add("--format=custom");
        args.add("--verbose");
        args.add("--file=" + hostFile);
        return new Command(tools.pgDump(), args, environment(server));
    }

    /** pg_restore --clean --if-exists --verbose into {@code database}. */
    public static Command restore(PgClientTools tools, ConnectionProfile server, String database, String hostFile) {
        var args = connectionArgs(server, database);
        args.add("--clean");
        args.add("--if-exists");
        args.add("--verbose");
        args.add(hostFile);
        return new Command(tools.pgRestore(), args, environment(server));
    }

    /** Lists a dump's table of contents — used to count restore steps. Needs no server. */
    public static Command listDumpToc(PgClientTools tools, String hostFile) {
        return new Command(tools.pgRestore(), List.of("--list", hostFile), Map.of());
    }

    private static List<String> connectionArgs(ConnectionProfile server, String database) {
        var args = new ArrayList<String>();
        args.add("--host=" + server.host);
        args.add("--port=" + server.port);
        args.add("--username=" + server.username);
        args.add("--dbname=" + database);
        args.add("--no-password");
        return args;
    }

    /** PGPASSWORD / PGSSLMODE for the child process, mirroring the app's own connection. */
    public static Map<String, String> environment(ConnectionProfile server) {
        var env = new HashMap<String, String>();
        env.put("PGSSLMODE", (server.sslMode == null ? PgSslMode.PREFER : server.sslMode).libpqName());
        env.put("PGAPPNAME", ConnectionProfile.APPLICATION_NAME);
        if (server.password != null && !server.password.isEmpty()) env.put("PGPASSWORD", server.password);
        return env;
    }
}
