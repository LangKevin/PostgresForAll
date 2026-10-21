package com.postgresforall.core.platform;

import com.postgresforall.core.util.CancelToken;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Native PostgreSQL installs on macOS:
 * <ul>
 *   <li>launchd jobs whose plist mentions postgres — Homebrew
 *       ({@code homebrew.mxcl.postgresql@16}) and the EDB installer
 *       ({@code com.edb.launchd.postgresql-16}); the data directory comes from
 *       the job's {@code -D} argument, the port from its postgresql.conf;</li>
 *   <li>Postgres.app servers (not launchd jobs; listed so they get a label).</li>
 * </ul>
 */
public final class MacPostgresServices implements LocalServiceSource {
    /** Name prefix for Postgres.app entries, which have no launchd label. */
    public static final String POSTGRES_APP_PREFIX = "postgresapp:";
    /** launchd label prefix of `brew services` jobs. */
    public static final String HOMEBREW_PREFIX = "homebrew.mxcl.";

    private final ProcessRunner runner;

    public MacPostgresServices(ProcessRunner runner) {
        this.runner = runner != null ? runner : new DefaultProcessRunner();
    }

    /** A parsed launchd job definition. */
    public record LaunchdJob(String label, Path plist, Domain domain, List<String> programArguments,
                             String dataDirectory) {
        public boolean isHomebrew() {
            return label.startsWith(HOMEBREW_PREFIX);
        }

        /** "postgresql@16" for a Homebrew label, else null. */
        public String homebrewFormula() {
            return isHomebrew() ? label.substring(HOMEBREW_PREFIX.length()) : null;
        }
    }

    /** Where a job is loaded: the user's GUI session, or the system (root) domain. */
    public enum Domain { USER, SYSTEM }

    public static List<Path> plistDirs() {
        return List.of(
                AppPaths.home().resolve("Library/LaunchAgents"),
                Path.of("/Library/LaunchAgents"),
                Path.of("/Library/LaunchDaemons"));
    }

    @Override
    public List<LocalPgService> list(CancelToken ct) {
        if (!Platform.isMac()) return List.of();
        var found = new ArrayList<LocalPgService>();
        try {
            var userJobs = runner.run("launchctl", List.of("list"), Duration.ofSeconds(5), ct);
            for (var job : findJobs(runner, ct)) {
                var port = LocalServiceSource.readPort(job.dataDirectory());
                var running = job.domain() == Domain.USER
                        ? parseLaunchctlList(userJobs.exitCode() == 0 ? userJobs.stdOut() : null, job.label())
                        : systemJobRunning(runner, job.label(), ct);
                found.add(new LocalPgService(job.label(), displayName(job), job.dataDirectory(), port, running));
            }
            // `brew services stop` deletes the plist, so a stopped Homebrew server
            // only shows up in brew's own list. Its data folder is var/<formula>.
            var brewList = brewServices(runner, ct);
            if (!brewList.isEmpty()) {
                var prefix = runner.run("brew", List.of("--prefix"), Duration.ofSeconds(20), ct);
                for (var b : brewList) {
                    var label = HOMEBREW_PREFIX + b.name();
                    if (found.stream().anyMatch(s -> s.name().equals(label))) continue;
                    var dataDir = prefix.exitCode() == 0
                            ? Path.of(prefix.stdOut().trim(), "var", b.name()).toString() : null;
                    found.add(new LocalPgService(label, "Homebrew " + b.name(), dataDir,
                            LocalServiceSource.readPort(dataDir), b.running()));
                }
            }
            found.addAll(postgresAppServers());
        } catch (RuntimeException ex) {
            // best-effort, like every discovery source
        }
        return found;
    }

    /** One Homebrew PostgreSQL formula as `brew services list` reports it. */
    public record BrewService(String name, boolean running) { }

    /** PostgreSQL formulae known to `brew services` (started or not); empty without Homebrew. */
    public static List<BrewService> brewServices(ProcessRunner runner, CancelToken ct) {
        var r = runner.run("brew", List.of("services", "list", "--json"), Duration.ofSeconds(30), ct);
        return r.exitCode() == 0 ? parseBrewServicesJson(r.stdOut()) : List.of();
    }

    /** Parses {@code brew services list --json}, keeping postgresql* formulae. */
    public static List<BrewService> parseBrewServicesJson(String json) {
        var list = new ArrayList<BrewService>();
        try {
            for (var n : com.postgresforall.core.util.Json.PLAIN.readTree(json)) {
                var name = n.path("name").asText("");
                if (!name.startsWith("postgresql")) continue;
                list.add(new BrewService(name, "started".equals(n.path("status").asText(""))));
            }
        } catch (Exception ignored) {
            // unexpected output: nothing listed
        }
        return list;
    }

    /** Every launchd job definition that runs PostgreSQL. */
    public static List<LaunchdJob> findJobs(ProcessRunner runner, CancelToken ct) {
        var jobs = new ArrayList<LaunchdJob>();
        for (var dir : plistDirs()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                for (var plist : s.filter(p -> {
                    var n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".plist") && n.contains("postgres");
                }).sorted().toList()) {
                    var domain = dir.startsWith("/Library/LaunchDaemons") ? Domain.SYSTEM : Domain.USER;
                    parseJob(plist, domain, runner, ct).ifPresent(jobs::add);
                }
            } catch (IOException ignored) {
                // unreadable folder
            }
        }
        return jobs;
    }

    /** The job for {@code label}, if a plist for it exists. */
    public static Optional<LaunchdJob> findJob(String label, ProcessRunner runner, CancelToken ct) {
        return findJobs(runner, ct).stream().filter(j -> j.label().equals(label)).findFirst();
    }

    static Optional<LaunchdJob> parseJob(Path plist, Domain domain, ProcessRunner runner, CancelToken ct) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(plist);
        } catch (IOException ex) {
            return Optional.empty();
        }
        // Binary plists exist; plutil turns them into XML.
        if (!new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.ISO_8859_1).contains("<?xml")
                && !new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.ISO_8859_1).contains("<plist")) {
            var r = runner.run("plutil", List.of("-convert", "xml1", "-o", "-", plist.toString()),
                    Duration.ofSeconds(5), ct);
            if (r.exitCode() != 0) return Optional.empty();
            bytes = r.stdOut().getBytes(StandardCharsets.UTF_8);
        }
        return parsePlist(bytes).map(p -> new LaunchdJob(p.label, plist, domain, p.args, p.dataDir));
    }

    record ParsedPlist(String label, List<String> args, String dataDir) { }

    /** Label, ProgramArguments and the data directory (-D / PGDATA) from an XML plist. */
    static Optional<ParsedPlist> parsePlist(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setExpandEntityReferences(false);
            var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            var dict = firstChildElement(doc.getDocumentElement(), "dict");
            if (dict == null) return Optional.empty();

            String label = null;
            var args = new ArrayList<String>();
            String pgdata = null;
            Element key = null;
            for (var n = dict.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e)) continue;
                if (e.getTagName().equals("key")) {
                    key = e;
                    continue;
                }
                if (key == null) continue;
                switch (key.getTextContent().trim()) {
                    case "Label" -> label = e.getTextContent().trim();
                    case "ProgramArguments" -> {
                        for (var c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                            if (c instanceof Element s && s.getTagName().equals("string")) args.add(s.getTextContent());
                        }
                    }
                    case "EnvironmentVariables" -> pgdata = dictValue(e, "PGDATA");
                    default -> { }
                }
                key = null;
            }
            if (label == null) return Optional.empty();
            var dataDir = dataDirFromArgs(args);
            return Optional.of(new ParsedPlist(label, args, dataDir != null ? dataDir : pgdata));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** The data directory from {@code -D <dir>} or {@code -D<dir>} in a job's arguments. */
    public static String dataDirFromArgs(List<String> args) {
        for (var i = 0; i < args.size(); i++) {
            var a = args.get(i);
            if (a.equals("-D") && i + 1 < args.size()) return args.get(i + 1);
            if (a.startsWith("-D") && a.length() > 2) return a.substring(2);
            // pg_ctl-style single string: "... -D /path ..."
            var m = Pattern.compile("-D\\s*(?:\"([^\"]+)\"|(\\S+))").matcher(a);
            if (a.contains(" ") && m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    private static String dictValue(Element dict, String wanted) {
        Element key = null;
        for (var n = dict.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            if (e.getTagName().equals("key")) {
                key = e;
            } else if (key != null) {
                if (key.getTextContent().trim().equals(wanted)) return e.getTextContent().trim();
                key = null;
            }
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    /**
     * Running state of a user-domain job from {@code launchctl list} output
     * ("PID\tStatus\tLabel"): a PID means running, "-" means loaded but stopped,
     * absent means not loaded (stopped). Null when the listing is unavailable.
     */
    public static Boolean parseLaunchctlList(String output, String label) {
        if (output == null) return null;
        for (var line : output.split("\n")) {
            var parts = line.trim().split("\\s+");
            if (parts.length >= 3 && parts[parts.length - 1].equals(label)) {
                return !parts[0].equals("-");
            }
        }
        return false;
    }

    /** {@code launchctl print system/<label>}: "state = running"; not loaded = stopped. */
    public static Boolean systemJobRunning(ProcessRunner runner, String label, CancelToken ct) {
        var r = runner.run("launchctl", List.of("print", "system/" + label), Duration.ofSeconds(5), ct);
        if (r.exitCode() != 0) {
            var err = (r.stdErr() + r.stdOut()).toLowerCase(Locale.ROOT);
            return err.contains("could not find service") || err.contains("not found") ? Boolean.FALSE : null;
        }
        var m = Pattern.compile("^\\s*state\\s*=\\s*(\\w+)", Pattern.MULTILINE).matcher(r.stdOut());
        return m.find() ? m.group(1).equals("running") : null;
    }

    private static String displayName(LaunchdJob job) {
        if (job.isHomebrew()) return "Homebrew " + job.homebrewFormula();
        if (job.label().startsWith("com.edb.launchd.")) return "EDB " + job.label().substring("com.edb.launchd.".length());
        return job.label();
    }

    /** Postgres.app data folders: ~/Library/Application Support/Postgres/var-NN. */
    static List<LocalPgService> postgresAppServers() {
        var root = AppPaths.home().resolve("Library/Application Support/Postgres");
        if (!Files.isDirectory(root)) return List.of();
        var list = new ArrayList<LocalPgService>();
        try (Stream<Path> s = Files.list(root)) {
            for (var dir : s.filter(d -> d.getFileName().toString().startsWith("var-") && Files.isDirectory(d))
                    .sorted().toList()) {
                var name = dir.getFileName().toString();
                list.add(new LocalPgService(POSTGRES_APP_PREFIX + name, "Postgres.app " + name.substring(4),
                        dir.toString(), LocalServiceSource.readPort(dir.toString()), null));
            }
        } catch (IOException ignored) {
            // nothing listed
        }
        return list;
    }
}
