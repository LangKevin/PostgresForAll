package com.postgresforall.core.testsupport;

import com.postgresforall.core.config.AppConfig;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Skips live-DB tests when the configured server is unreachable. */
public final class PostgresAvailableCondition implements ExecutionCondition {
    private static volatile Boolean reachable;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        var cfg = AppConfig.current();
        if (reachable == null) reachable = tryConnect(cfg.host, cfg.port);
        return reachable
                ? ConditionEvaluationResult.enabled("PostgreSQL reachable")
                : ConditionEvaluationResult.disabled("PostgreSQL not reachable on " + cfg.host + ":" + cfg.port
                        + " (from " + AppConfig.resolvePath() + ")");
    }

    private static boolean tryConnect(String host, int port) {
        try (var s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
