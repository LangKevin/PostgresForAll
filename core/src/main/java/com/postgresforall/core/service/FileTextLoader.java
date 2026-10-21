package com.postgresforall.core.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a script into an editor tab, with a size guard. */
public final class FileTextLoader {
    private FileTextLoader() { }

    /** Max file size we'll load into an editor tab (1 MiB). */
    public static final long MAX_BYTES = 1024L * 1024;

    public static String readText(Path path) throws IOException {
        if (path == null || path.toString().isBlank()) throw new IllegalArgumentException("Path is empty.");
        if (!Files.isRegularFile(path)) throw new FileNotFoundException("File not found: " + path);
        var size = Files.size(path);
        if (size > MAX_BYTES) {
            throw new IllegalStateException(String.format(
                    "File is %,d bytes — exceeds the %,d byte safety cap.", size, MAX_BYTES));
        }
        var text = Files.readString(path, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
