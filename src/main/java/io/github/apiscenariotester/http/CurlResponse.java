package io.github.apiscenariotester.http;

import java.nio.file.Path;

public record CurlResponse(int status, int exitCode, double elapsedMs, String headers, String body,
        String error, Path logFile) { }
