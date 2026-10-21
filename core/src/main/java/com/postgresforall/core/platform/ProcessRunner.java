package com.postgresforall.core.platform;

import com.postgresforall.core.util.CancelToken;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Runs a child process to completion and captures its output. Seam for tests. */
public interface ProcessRunner {

    /**
     * @param environment extra environment variables for the child (e.g. PGPASSWORD),
     *                    so secrets never appear on the command line. Null leaves it as-is.
     */
    ProcessResult run(String file, List<String> args, Duration timeout,
                      CancelToken ct, Map<String, String> environment);

    default ProcessResult run(String file, List<String> args, Duration timeout) {
        return run(file, args, timeout, CancelToken.NONE, null);
    }

    default ProcessResult run(String file, List<String> args, Duration timeout, CancelToken ct) {
        return run(file, args, timeout, ct, null);
    }
}
