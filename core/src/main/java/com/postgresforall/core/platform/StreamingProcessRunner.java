package com.postgresforall.core.platform;

import com.postgresforall.core.util.CancelToken;

import java.util.List;
import java.util.Map;

public interface StreamingProcessRunner {

    /** Receives one line of child output; {@code isStdErr} says which stream. */
    @FunctionalInterface
    interface LineHandler {
        void onLine(boolean isStdErr, String line);
    }

    /**
     * Starts a process and calls {@code onLine} for every stdout / stderr line as
     * it arrives. Returns the exit code. Cancellation kills the process tree and
     * returns -1.
     */
    int stream(String file, List<String> args, LineHandler onLine, CancelToken ct,
               Map<String, String> environment);
}
