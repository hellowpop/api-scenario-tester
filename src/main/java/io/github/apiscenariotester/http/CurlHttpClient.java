package io.github.apiscenariotester.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owns temporary curl process files. Calls are deliberately synchronous. */
public final class CurlHttpClient implements AutoCloseable {
    private final String executable;
    private final Path workspace;
    private final Path cookies;
    private final Path logDirectory;

    public CurlHttpClient(String executable, Path logDirectory) throws IOException {
        this.executable = executable;
        this.logDirectory = logDirectory;
        if (logDirectory != null) Files.createDirectories(logDirectory);
        workspace = Files.createTempDirectory("api-scenario-curl-");
        cookies = Files.createFile(workspace.resolve("cookies.txt"));
    }

    public CurlResponse execute(CurlRequest request) throws IOException, InterruptedException {
        String id = UUID.randomUUID().toString();
        Path stdin = workspace.resolve(id + ".in");
        Path stdout = workspace.resolve(id + ".out");
        Path stderr = workspace.resolve(id + ".err");
        Path responseBody = workspace.resolve(id + ".body");
        Path responseHeaders = workspace.resolve(id + ".headers");
        Path trace = workspace.resolve(id + ".trace");
        Path logFile = logDirectory == null ? null : logDirectory.resolve(id + ".txt");
        Files.writeString(stdin, request.body(), StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of(executable, "--disable", "--silent", "--show-error",
                "--globoff", "--proto", "=http,https", "--connect-timeout", seconds(request.connectTimeoutMs()),
                "--max-time", seconds(request.connectTimeoutMs() + request.readTimeoutMs()),
                "--cookie", cookies.toString(), "--cookie-jar", cookies.toString(),
                "--output", responseBody.toString(), "--dump-header", responseHeaders.toString(),
                "--write-out", "%{http_code}\n%{time_total}"));
        if (request.method().equals("HEAD")) args.add("--head");
        else args.addAll(List.of("--request", request.method()));
        request.headers().forEach((name, value) -> args.addAll(List.of("--header",
                value.isEmpty() ? name + ";" : name + ": " + value)));
        if (!request.body().isEmpty()) args.addAll(List.of("--data-binary", "@-"));
        if (logFile != null) args.addAll(List.of("--trace-ascii", trace.toString(), "--trace-time"));
        args.addAll(List.of("--url", request.url()));

        Process process = null;
        int exitCode = -1;
        String error = "";
        long start = System.nanoTime();
        try {
            process = new ProcessBuilder(args).redirectInput(stdin.toFile())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            long guardMs = request.connectTimeoutMs() + request.readTimeoutMs() + 2000;
            if (!process.waitFor(guardMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                exitCode = 28;
                error = "curl process exceeded its timeout";
            } else exitCode = process.exitValue();
        } catch (IOException exception) {
            error = "Unable to start curl: " + exception.getMessage();
        } catch (InterruptedException exception) {
            if (process != null) { process.destroyForcibly(); process.waitFor(); }
            if (logFile != null) writeLog(logFile, args, request, read(trace), read(stdout),
                    read(stderr) + "\nInterrupted", read(responseHeaders), read(responseBody), -1);
            throw exception;
        }
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        String output = read(stdout);
        String errors = read(stderr);
        int status = 0;
        String[] values = output.strip().split("\\s+");
        if (values.length == 2) {
            try {
                status = Integer.parseInt(values[0]);
                elapsedMs = Double.parseDouble(values[1]) * 1000;
            } catch (NumberFormatException exception) {
                if (error.isEmpty()) error = "Invalid curl response metadata: " + output;
            }
        } else if (exitCode == 0) error = "Missing curl response metadata";
        if (exitCode != 0 && error.isEmpty()) error = "curl exit " + exitCode + ": " + errors.strip();
        String headers = read(responseHeaders);
        String body = request.method().equals("HEAD") ? "" : read(responseBody);
        if (logFile != null) writeLog(logFile, args, request, read(trace), output,
                errors + (error.isEmpty() ? "" : "\n" + error), headers, body, exitCode);
        // Remove call files promptly; only the cookie jar survives until the next call.
        for (Path file : List.of(stdin, stdout, stderr, responseBody, responseHeaders, trace)) Files.deleteIfExists(file);
        return new CurlResponse(status, exitCode, elapsedMs, headers, body, error, logFile);
    }

    private static String seconds(long millis) { return String.format(Locale.ROOT, "%.3f", millis / 1000.0); }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
    }

    private static void writeLog(Path output, List<String> args, CurlRequest request, String trace,
            String stdout, String stderr, String headers, String body, int exitCode) throws IOException {
        // JSON preserves argument boundaries, quotes and backslashes without suggesting a shell invocation.
        String text = "COMMAND (argument array)\n" + new ObjectMapper().writeValueAsString(args)
                + "\n\nSTDIN (UTF-8)\n" + request.body() + "\n\nTRACE\n" + trace
                + "\n\nSTDOUT\n" + stdout + "\n\nSTDERR\n" + stderr
                + "\n\nRESPONSE HEADERS\n" + headers + "\n\nRESPONSE BODY (UTF-8)\n" + body
                + "\n\nEXIT CODE: " + exitCode + "\n";
        Files.writeString(output, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    @Override
    public void close() throws IOException {
        try (var files = Files.list(workspace)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(workspace);
    }
}
