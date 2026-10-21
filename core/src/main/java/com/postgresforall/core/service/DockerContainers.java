package com.postgresforall.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.util.CancelToken;
import com.postgresforall.core.util.Json;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Optional Docker enrichment for discovery: which container publishes which
 * host port, and (opt-in) the POSTGRES_USER / POSTGRES_PASSWORD a container
 * was started with. Every call quietly returns nothing when Docker isn't
 * installed or isn't running — Docker is never required.
 */
public final class DockerContainers {
    private final ProcessRunner runner;

    public DockerContainers(ProcessRunner runner) {
        this.runner = runner != null ? runner : new DefaultProcessRunner();
    }

    public DockerContainers() {
        this(null);
    }

    /** A running container and the host ports it publishes. */
    public record ContainerPorts(String name, String image, List<Integer> hostPorts) {
        private static final Pattern PG_IMAGE =
                Pattern.compile("postgres|postgis|timescale|pgvector|supabase/postgres", Pattern.CASE_INSENSITIVE);

        /** Heuristic: the image is a PostgreSQL (or Postgres-based) image. */
        public boolean looksLikePostgres() {
            return PG_IMAGE.matcher(image).find();
        }
    }

    public record PostgresEnv(String user, String password, String database) {
        static final PostgresEnv NONE = new PostgresEnv(null, null, null);
    }

    public List<ContainerPorts> listRunning(CancelToken ct) {
        var r = runner.run("docker", List.of("ps", "--format", "{{.Names}}\t{{.Image}}\t{{.Ports}}"),
                Duration.ofSeconds(5), ct);
        return r.exitCode() == 0 ? parsePs(r.stdOut()) : List.of();
    }

    private static final Pattern PUBLISHED = Pattern.compile(":(\\d+)->\\d+/tcp");

    /** Parses {@code docker ps --format "{{.Names}}\t{{.Image}}\t{{.Ports}}"} output. */
    public static List<ContainerPorts> parsePs(String stdout) {
        var list = new ArrayList<ContainerPorts>();
        for (var raw : stdout.split("\n")) {
            var line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) continue;
            var parts = line.split("\t", -1);
            if (parts.length < 2 || parts[0].isBlank()) continue;
            var ports = new LinkedHashSet<Integer>();
            if (parts.length > 2) {
                var m = PUBLISHED.matcher(parts[2]);
                while (m.find()) ports.add(Integer.parseInt(m.group(1)));
            }
            list.add(new ContainerPorts(parts[0].trim(), parts[1].trim(), List.copyOf(ports)));
        }
        return list;
    }

    /** POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB from the container's environment. */
    public PostgresEnv readPostgresEnv(String container, CancelToken ct) {
        var r = runner.run("docker", List.of("inspect", "--format", "{{json .Config.Env}}", container),
                Duration.ofSeconds(5), ct);
        return r.exitCode() == 0 ? parseEnv(r.stdOut()) : PostgresEnv.NONE;
    }

    public static PostgresEnv parseEnv(String json) {
        try {
            List<String> env = Json.PLAIN.readValue(json.trim(), new TypeReference<List<String>>() { });
            if (env == null) env = List.of();
            var user = get(env, "POSTGRES_USER");
            var pw = get(env, "POSTGRES_PASSWORD");
            // The official image defaults the superuser to "postgres".
            return new PostgresEnv(user != null ? user : (pw == null ? null : "postgres"), pw, get(env, "POSTGRES_DB"));
        } catch (Exception ex) {
            return PostgresEnv.NONE;
        }
    }

    private static String get(List<String> env, String key) {
        for (var e : env) {
            if (e.startsWith(key + "=")) return e.substring(key.length() + 1);
        }
        return null;
    }
}
