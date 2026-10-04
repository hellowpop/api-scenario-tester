package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioYamlCodec;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandTest {
    @TempDir Path directory;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private String baseUrl;
    private final StringWriter console = new StringWriter();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body
                    + " cookie=" + exchange.getRequestHeaders().getFirst("Cookie")
                    + " empty=" + exchange.getRequestHeaders().getFirst("X-Empty"));
            if (exchange.getRequestURI().getPath().equals("/slow")) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (exchange.getRequestURI().getPath().equals("/login")) {
                exchange.getResponseHeaders().add("Set-Cookie", "session=test; Path=/");
            }
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            byte[] response = ("응답: " + body).getBytes(StandardCharsets.UTF_8);
            int status = exchange.getRequestURI().getPath().equals("/fail") ? 500 : 200;
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, response.length);
                try { exchange.getResponseBody().write(response); } catch (java.io.IOException ignored) { }
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void debugRecordsCurlInputOutputAndCreatesRelativeExcelHyperlink() throws Exception {
        String body = "{\"name\":\"한글 & $(literal) `quote`\"}\nsecond line";
        Path scenario = scenario(List.of(row("1", "POST", "/echo", body)), false);
        Path output = directory.resolve("공백 폴더/results.xlsx");

        assertThat(run(scenario, output, "--debug")).as(console.toString()).isZero();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).contains("POST /echo " + body);
        try (var stream = Files.newInputStream(output); var workbook = new XSSFWorkbook(stream)) {
            Row row = workbook.getSheet("calls").getRow(1);
            Cell link = cell(workbook, row, "curlLog");
            assertThat(link.getHyperlink().getType()).isEqualTo(HyperlinkType.FILE);
            String address = link.getHyperlink().getAddress();
            assertThat(address).matches("curl/[0-9a-f-]{36}\\.txt");
            UUID.fromString(address.substring(5, address.length() - 4));
            String log = Files.readString(output.getParent().resolve(address));
            assertThat(log).contains("COMMAND", "STDIN", "STDOUT", "STDERR", "TRACE", "EXIT CODE: 0", body,
                    "응답: " + body, "=> Send header", "<= Recv header");
            assertThat(cell(workbook, row, "status").getNumericCellValue()).isEqualTo(200);
            assertThat(cell(workbook, row, "success").getBooleanCellValue()).isTrue();
        }
    }

    @Test
    void normalRunDoesNotCreateCurlLogsOrHyperlinks() throws Exception {
        Path output = directory.resolve("normal/results.xlsx");
        assertThat(run(scenario(List.of(row("1", "GET", "/health", "")), false), output)).isZero();
        assertThat(output.getParent().resolve("curl")).doesNotExist();
        try (var stream = Files.newInputStream(output); var workbook = new XSSFWorkbook(stream)) {
            Cell link = cell(workbook, workbook.getSheet("calls").getRow(1), "curlLog");
            assertThat(link.getHyperlink()).isNull();
            assertThat(link.getStringCellValue()).isEmpty();
        }
    }

    @Test
    void sequentialIterationsUseUniqueLogFilesAndKeepCookies() throws Exception {
        List<Map<String, String>> rows = List.of(row("2", "GET", "/check", ""), row("1", "GET", "/login", ""));
        Path scenario = writeScenario(Map.of("host.api.baseUrl", baseUrl, "iterations", "2", "sessions", "1"), List.of(), rows);
        Path output = directory.resolve("repeat.xlsx");
        assertThat(run(scenario, output, "--debug")).isZero();
        assertThat(requests).hasSize(4);
        assertThat(requests.get(0)).contains("/login");
        assertThat(requests.get(1)).contains("/check", "cookie=session=test");
        assertThat(requests.get(2)).contains("/login");
        assertThat(requests.get(3)).contains("/check");
        try (var files = Files.list(directory.resolve("curl"))) { assertThat(files.count()).isEqualTo(4); }
    }

    @Test
    void httpFailureRetainsResponseLogAndStopsFollowingCalls() throws Exception {
        Path output = directory.resolve("failure.xlsx");
        assertThat(run(scenario(List.of(row("1", "GET", "/fail", ""), row("2", "GET", "/health", "")), false), output, "--debug")).isEqualTo(1);
        assertThat(requests).hasSize(1);
        try (var stream = Files.newInputStream(output); var workbook = new XSSFWorkbook(stream)) {
            Row row = workbook.getSheet("calls").getRow(1);
            assertThat(cell(workbook, row, "status").getNumericCellValue()).isEqualTo(500);
            assertThat(cell(workbook, row, "success").getBooleanCellValue()).isFalse();
            assertThat(cell(workbook, row, "curlLog").getHyperlink()).isNotNull();
        }
    }

    @Test
    void continueOnFailureRunsRemainingCallsAndReturnsFailure() throws Exception {
        assertThat(run(scenario(List.of(row("1", "GET", "/fail", ""), row("2", "GET", "/health", "")), true), directory.resolve("continue.xlsx"))).isEqualTo(1);
        assertThat(requests).hasSize(2);
    }

    @Test
    void missingCurlExecutableProducesFailureRowAndDebugLog() throws Exception {
        Path config = directory.resolve("runtime.yml");
        Files.writeString(config, "curlExecutable: definitely-missing-curl-" + UUID.randomUUID());
        Path output = directory.resolve("missing.xlsx");
        assertThat(run(scenario(List.of(row("1", "GET", "/health", "")), false), output, "--debug", "--config", config.toString())).isEqualTo(1);
        assertThat(requests).isEmpty();
        try (var stream = Files.newInputStream(output); var workbook = new XSSFWorkbook(stream)) {
            Row row = workbook.getSheet("calls").getRow(1);
            String address = cell(workbook, row, "curlLog").getHyperlink().getAddress();
            assertThat(Files.readString(directory.resolve(address))).contains("COMMAND", "STDERR", "EXIT CODE: -1");
            assertThat(cell(workbook, row, "success").getBooleanCellValue()).isFalse();
        }
    }

    @Test
    void curlTimeoutProducesLinkedFailureLog() throws Exception {
        Path scenario = writeScenario(Map.of("host.api.baseUrl", baseUrl, "host.api.connectTimeoutMs", "50", "host.api.readTimeoutMs", "50"), List.of(), List.of(row("1", "GET", "/slow", "")));
        Path output = directory.resolve("timeout.xlsx");
        assertThat(run(scenario, output, "--debug")).isEqualTo(1);
        try (var stream = Files.newInputStream(output); var workbook = new XSSFWorkbook(stream)) {
            Row row = workbook.getSheet("calls").getRow(1);
            assertThat(cell(workbook, row, "curlExitCode").getNumericCellValue()).isEqualTo(28);
            assertThat(cell(workbook, row, "curlLog").getHyperlink()).isNotNull();
        }
    }

    @Test
    void invalidLaterRowPreventsAllHttpCalls() throws Exception {
        Map<String, String> invalid = new LinkedHashMap<>(row("2", "GET", "/health", ""));
        invalid.put("headers", "not-json");
        Path output = directory.resolve("invalid.xlsx");
        assertThat(run(scenario(List.of(row("1", "GET", "/health", ""), invalid), false), output, "--debug")).isEqualTo(2);
        assertThat(requests).isEmpty();
        assertThat(output).doesNotExist();
    }

    @Test
    void existingResultIsRenamedBeforeCallingApiAndNewResultIsWritten() throws Exception {
        Path output = directory.resolve("existing.xlsx");
        Files.writeString(output, "preserve me");
        Path input = scenario(List.of(row("1", "GET", "/health", "")), false);
        assertThat(run(input, output)).isZero();
        assertThat(requests).hasSize(1);
        try (var files = Files.list(directory)) {
            var backups = files.filter(file -> file.getFileName().toString().matches("existing_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?\\.xlsx")).toList();
            assertThat(backups).hasSize(1);
            java.time.LocalDateTime.parse(backups.getFirst().getFileName().toString().substring("existing_".length(),"existing_".length()+19),java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS"));
            assertThat(Files.readString(backups.getFirst())).isEqualTo("preserve me");
        }
        byte[] firstResult = Files.readAllBytes(output);
        assertThat(run(input, output)).isZero();
        assertThat(requests).hasSize(2);
        try (var files = Files.list(directory)) {
            var backups = files.filter(file -> file.getFileName().toString().matches("existing_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?\\.xlsx")).toList();
            assertThat(backups).hasSize(2);
            assertThat(backups.stream().anyMatch(file -> {
                try { return java.util.Arrays.equals(Files.readAllBytes(file),firstResult); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            })).isTrue();
        }
        try (var workbook = new XSSFWorkbook(output.toFile())) { assertThat(workbook.getSheet("calls").getLastRowNum()).isEqualTo(1); }
    }

    @Test
    void rejectsParallelSessionsAndInvalidScriptsBeforeCallingApi() throws Exception {
        Path parallel = writeScenario(Map.of("host.api.baseUrl", baseUrl, "sessions", "2"), List.of(), List.of(row("1", "GET", "/health", "")));
        assertThat(run(parallel, directory.resolve("parallel.xlsx"))).isEqualTo(2);
        Path scripted = writeScenario(Map.of("host.api.baseUrl", baseUrl), List.of(Map.of("id", "pre", "phase", "PRE", "body", "if (")), List.of(row("1", "GET", "/health", "")));
        assertThat(run(scripted, directory.resolve("scripted.xlsx"))).isEqualTo(2);
        assertThat(requests).isEmpty();
    }

    @Test void invalidScenarioDoesNotRenameExistingResult() throws Exception {
        Path output = directory.resolve("keep.xlsx"); Files.writeString(output,"keep result");
        Path input = writeScenario(Map.of("host.api.baseUrl",baseUrl,"sessions","2"),List.of(),List.of(row("1","GET","/health","")));
        assertThat(run(input,output)).isEqualTo(2); assertThat(requests).isEmpty();
        assertThat(Files.readString(output)).isEqualTo("keep result");
        try (var files = Files.list(directory)) { assertThat(files.noneMatch(file -> file.getFileName().toString().matches("keep_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?\\.xlsx"))).isTrue(); }
    }

    @Test
    void headAndRuntimeGlobalsAreHandledWithoutShellExpansion() throws Exception {
        Path config = directory.resolve("globals.yml");
        Files.writeString(config, "globals:\n  resource: health\nhosts:\n  api:\n    baseUrl: '" + baseUrl + "'\n");
        Path scenario = writeScenario(Map.of("host.api.baseUrl", "https://unused.invalid"), List.of(), List.of(row("1", "HEAD", "/${global.resource}", "")));
        assertThat(run(scenario, directory.resolve("head.xlsx"), "--config", config.toString())).isZero();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).startsWith("HEAD /health");
    }

    @Test
    void validateChecksInputWithoutInvokingCurl() throws Exception {
        Path scenario = scenario(List.of(row("1", "GET", "/health", "")), false);
        assertThat(new RootCommand().execute(console, "validate", "--scenario", scenario.toString())).isZero();
        assertThat(requests).isEmpty();
        assertThat(new RootCommand().execute(console, "run")).isEqualTo(2);
    }

    @Test
    void headerNulInLaterRowIsRejectedBeforeAnyHttpSideEffect() throws Exception {
        Map<String, String> invalid = new LinkedHashMap<>(row("2", "GET", "/health", ""));
        invalid.put("headers", "{\"X-Test\":\"bad\\u0000value\"}");
        Path input = scenario(List.of(row("1", "GET", "/health", ""), invalid), false);
        assertThat(new RootCommand().execute(console, "validate", "--scenario", input.toString())).isEqualTo(2);
        assertThat(run(input, directory.resolve("nul.xlsx"))).isEqualTo(2);
        assertThat(requests).isEmpty();
    }

    @Test
    void explicitlyEmptyHeaderIsSentInsteadOfSuppressedByCurl() throws Exception {
        Map<String, String> request = new LinkedHashMap<>(row("1", "GET", "/health", ""));
        request.put("headers", "{\"X-Empty\":\"\"}");
        assertThat(run(scenario(List.of(request), false), directory.resolve("empty-header.xlsx"))).isZero();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).endsWith(" empty=");
    }

    private int run(Path scenario, Path output, String... extra) {
        List<String> args = new ArrayList<>(List.of("run", "--scenario", scenario.toString(), "--output", output.toString()));
        args.addAll(List.of(extra));
        return new RootCommand().execute(console, args.toArray(String[]::new));
    }

    private Path scenario(List<Map<String, String>> rows, boolean continueOnFailure) throws Exception {
        return writeScenario(Map.of("host.api.baseUrl", baseUrl, "continueOnFailure", Boolean.toString(continueOnFailure)), List.of(), rows);
    }

    private Path writeScenario(Map<String, String> common, List<Map<String, String>> scripts, List<Map<String, String>> rows) throws Exception {
        Path input = directory.resolve(UUID.randomUUID() + ".yml");
        new ScenarioYamlCodec().write(new ScenarioDocument(1, common, Map.of("output", "results.xlsx"), scripts, List.of(), rows), input);
        return input;
    }

    private static Map<String, String> row(String order, String method, String path, String body) {
        return Map.of("order", order, "name", "call-" + order, "enabled", "true", "host", "api", "method", method,
                "path", path, "body", body, "headers", "{\"X-Test\":\"literal & value\"}", "expectedStatus", "200-299");
    }

    private static Cell cell(XSSFWorkbook workbook, Row row, String name) {
        for (Cell header : workbook.getSheet("calls").getRow(0)) {
            if (header.getStringCellValue().equals(name)) return row.getCell(header.getColumnIndex());
        }
        throw new AssertionError("missing column " + name);
    }
}
