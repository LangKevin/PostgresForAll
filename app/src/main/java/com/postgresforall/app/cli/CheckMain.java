package com.postgresforall.app.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Entry point of the console launcher: same as {@code PostgresForAll --check ...}. */
public final class CheckMain {
    private CheckMain() { }

    public static void main(String[] args) {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println();
        System.exit(CheckCommand.run(args, out));
    }
}
