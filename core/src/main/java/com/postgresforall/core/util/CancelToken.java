package com.postgresforall.core.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cooperative cancellation, the Java stand-in for .NET's CancellationToken.
 * Work checks {@link #isCancelled()} between steps, and anything that blocks
 * (a running statement, a child process) registers a callback that aborts it.
 */
public class CancelToken {

    /** A token that is never cancelled. */
    public static final CancelToken NONE = new CancelToken() {
        @Override
        public void cancel() {
            // never cancels
        }
    };

    private volatile boolean cancelled;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public void cancel() {
        if (cancelled) return;
        cancelled = true;
        for (var r : callbacks) {
            try { r.run(); } catch (RuntimeException ignored) { }
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() {
        if (cancelled) throw new CancelledException();
    }

    /**
     * Runs {@code onCancel} when this token is cancelled (immediately if it
     * already is). Close the returned registration once the guarded work ends.
     */
    public Registration register(Runnable onCancel) {
        callbacks.add(onCancel);
        if (cancelled) {
            try { onCancel.run(); } catch (RuntimeException ignored) { }
        }
        return () -> callbacks.remove(onCancel);
    }

    /** A child token cancelled with this one, or on its own. */
    public CancelToken linked() {
        var child = new CancelToken();
        register(child::cancel);
        return child;
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** Thrown when work stops because its token was cancelled. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Operation cancelled.");
        }
    }
}
