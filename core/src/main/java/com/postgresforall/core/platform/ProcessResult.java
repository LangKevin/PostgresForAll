package com.postgresforall.core.platform;

public record ProcessResult(int exitCode, String stdOut, String stdErr) {
}
