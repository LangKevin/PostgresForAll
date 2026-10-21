package com.postgresforall.core.platform;

import com.postgresforall.core.util.CancelToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DefaultProcessRunner implements ProcessRunner, StreamingProcessRunner {

    @Override
    public int stream(String file, List<String> args, LineHandler onLine, CancelToken ct,
                      Map<String, String> environment) {
        Process p;
        try {
            p = start(file, args, environment);
        } catch (IOException ex) {
            onLine.onLine(true, "failed to start process: " + ex.getMessage());
            return -1;
        }

        // Both streams feed one handler; serialise so callers see whole lines.
        var gate = new Object();
        var out = pump(p.getInputStream(), line -> { synchronized (gate) { onLine.onLine(false, line); } });
        var err = pump(p.getErrorStream(), line -> { synchronized (gate) { onLine.onLine(true, line); } });

        try (var ignored = ct.register(() -> kill(p))) {
            var code = p.waitFor();
            out.join();
            err.join();
            return ct.isCancelled() ? -1 : code;
        } catch (InterruptedException ex) {
            kill(p);
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public ProcessResult run(String file, List<String> args, Duration timeout, CancelToken ct,
                             Map<String, String> environment) {
        Process p;
        try {
            p = start(file, args, environment);
        } catch (IOException ex) {
            return new ProcessResult(-1, "", ex.getMessage());
        }

        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        var out = pump(p.getInputStream(), line -> { synchronized (stdout) { stdout.append(line).append('\n'); } });
        var err = pump(p.getErrorStream(), line -> { synchronized (stderr) { stderr.append(line).append('\n'); } });

        try (var ignored = ct.register(() -> kill(p))) {
            var finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished || ct.isCancelled()) {
                kill(p);
                out.join(1000);
                err.join(1000);
                var why = ct.isCancelled()
                        ? "\n[cancelled]"
                        : String.format("%n[timeout after %.1fs]", timeout.toMillis() / 1000.0);
                return new ProcessResult(-1, text(stdout), text(stderr) + why);
            }
            out.join();
            err.join();
            return new ProcessResult(p.exitValue(), text(stdout), text(stderr));
        } catch (InterruptedException ex) {
            kill(p);
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, text(stdout), text(stderr) + "\n[interrupted]");
        }
    }

    private static Process start(String file, List<String> args, Map<String, String> environment)
            throws IOException {
        var command = new ArrayList<String>(args.size() + 1);
        command.add(Executables.resolve(file));
        command.addAll(args);
        var pb = new ProcessBuilder(command);
        if (!Platform.isWindows()) pb.environment().put("PATH", Executables.childPath());
        if (environment != null) pb.environment().putAll(environment);
        var p = pb.start();
        p.getOutputStream().close(); // nothing reads stdin; a closed pipe stops tools blocking on a prompt
        return p;
    }

    private static Thread pump(InputStream stream, Consumer<String> onLine) {
        return Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) onLine.accept(line);
            } catch (IOException ignored) {
                // stream closed by a kill
            }
        });
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static String text(StringBuilder sb) {
        synchronized (sb) {
            return sb.toString();
        }
    }
}
