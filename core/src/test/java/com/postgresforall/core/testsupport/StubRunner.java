package com.postgresforall.core.testsupport;

import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.util.CancelToken;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/** A ProcessRunner that answers from a handler and records every call. */
public final class StubRunner implements ProcessRunner {
    public record Call(String file, List<String> args) {
        public String line() {
            return file + " " + String.join(" ", args);
        }
    }

    private final BiFunction<String, List<String>, ProcessResult> handler;
    public final List<Call> calls = new CopyOnWriteArrayList<>();

    public StubRunner(BiFunction<String, List<String>, ProcessResult> handler) {
        this.handler = handler;
    }

    @Override
    public ProcessResult run(String file, List<String> args, Duration timeout, CancelToken ct,
                             Map<String, String> environment) {
        calls.add(new Call(file, List.copyOf(args)));
        return handler.apply(file, args);
    }

    /** A machine without Docker: every docker call fails to start; anything else exits 1. */
    public static StubRunner noDocker() {
        return new StubRunner((file, args) -> file.equals("docker")
                ? new ProcessResult(-1, "", "The system cannot find the file specified.")
                : new ProcessResult(1, "", ""));
    }
}
