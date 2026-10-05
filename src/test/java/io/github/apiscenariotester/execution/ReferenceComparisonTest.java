package io.github.apiscenariotester.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.apiscenariotester.cli.RootCommand;
import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioYamlCodec;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceComparisonTest {
    @TempDir Path directory;
    private HttpServer base, reference;
    private java.util.concurrent.ExecutorService handlers;
    private final List<String> baseCalls = new CopyOnWriteArrayList<>(), referenceCalls = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        base = server("base", baseCalls); reference = server("reference", referenceCalls);
    }
    private HttpServer server(String label, List<String> calls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(exchange.getRequestMethod() + " " + path + " body=" + body + " cookie=" + exchange.getRequestHeaders().getFirst("Cookie"));
            if (path.contains("login")) {
                String session = path.substring(path.lastIndexOf('/') + 1);
                exchange.getResponseHeaders().add("X-Token", label + session);
                exchange.getResponseHeaders().add("Set-Cookie", "sid=" + label + session + "; Path=/");
            }
            exchange.getResponseHeaders().add("X-Server", label);
            String responseText = path.contains("login") ? "login" : label + " response";
            if (path.contains("/skip")) responseText = "{\"name\":\"same\",\"server\":\"" + label +
                    "\",\"nested\":{\"timestamp\":\"" + label + "\"},\"users\":[{\"id\":1,\"updatedAt\":\"" + label + "\"}]}";
            byte[] response = responseText.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(label.equals("reference") && path.contains("different-status") ? 201 : 200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start(); return server;
    }
    @AfterEach void stop() { base.stop(0); reference.stop(0); handlers.shutdownNow(); }

    @Test void runtimeReferenceHostComparesSubsetAndMainWithSessionCookiesAndLogLinks() throws Exception {
        Path config = directory.resolve("runtime.yml");
        Files.writeString(config, "hosts:\n  api:\n    referenceUrl: '" + url(reference) + "/ref'\n");
        Files.writeString(directory.resolve("body.txt"), "${global.token}\n한글");
        var login = Map.of("subsetId", "login", "order", "1", "name", "login", "host", "api", "method", "GET",
                "path", "/login/${scenario.session}", "postScripts", "token");
        var scripts = List.of(Map.of("id", "token", "phase", "POST", "body", "global.token = response.headers['x-token'][0]"));
        Path input = write(Map.of("sessions", "2", "host.api.baseUrl", url(base) + "/base", "host.api.referenceUrl", "invalid-overridden"),
                scripts, List.of(login), List.of(Map.of("order", "1", "name", "login", "method", "SUBSET", "path", "login"),
                        row(2, "/different-status?key=%2F", "ref:body.txt")));
        Path output = directory.resolve("result.xlsx"); var console = new StringWriter();
        assertThat(new RootCommand().execute(console, "run", "--scenario", input.toString(), "--config", config.toString(),
                "--output", output.toString(), "--debug")).as(console.toString()).isZero();
        assertThat(baseCalls).hasSize(4); assertThat(referenceCalls).hasSize(4);
        for (int session = 1; session <= 2; session++) {
            assertThat(baseCalls).anySatisfy(call -> assertThat(call).contains("/base/different-status?key=%2F body=base", "cookie=sid=base"));
            final String id = Integer.toString(session);
            assertThat(referenceCalls).anySatisfy(call -> assertThat(call).contains("/ref/different-status?key=%2F body=base" + id + "\n한글", "cookie=sid=reference" + id));
        }
        try (var files = Files.list(directory.resolve("curl"))) { assertThat(files.count()).isEqualTo(8); }
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            var calls = workbook.getSheet("calls"); assertThat(calls.getLastRowNum()).isEqualTo(4);
            for (int index = 1; index <= 4; index++) {
                Row row = calls.getRow(index);
                assertThat(cell(calls, row, "headersMatch").getBooleanCellValue()).isFalse();
                assertThat(cell(calls, row, "responseMatch").getBooleanCellValue()).isEqualTo(index % 2 == 1);
                assertThat(cell(calls, row, "statusMatch").getBooleanCellValue()).isEqualTo(index % 2 == 1);
                assertThat(cell(calls, row, "comparisonDetail").getStringCellValue()).contains("x-server");
                assertThat(cell(calls, row, "success").getBooleanCellValue()).isTrue();
                for (String column : List.of("curlLog", "referenceCurlLog")) {
                    Path log = directory.resolve(cell(calls, row, column).getHyperlink().getAddress());
                    assertThat(log).isRegularFile(); assertThat(Files.readString(log)).contains("--insecure");
                }
            }
        }
    }

    @Test void referenceConnectionFailureIsReportedWithoutChangingBaseSuccess() throws Exception {
        String dead = url(reference); reference.stop(0);
        Path input = write(Map.of("host.api.baseUrl", url(base), "host.api.referenceUrl", dead,
                "host.api.connectTimeoutMs", "100", "host.api.readTimeoutMs", "100"), List.of(), List.of(), List.of(row(1, "/echo", "")));
        Path output = directory.resolve("failure.xlsx"); var console = new StringWriter();
        assertThat(new RootCommand().execute(console, "run", "--scenario", input.toString(), "--output", output.toString(), "--debug"))
                .as(console.toString()).isZero();
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet calls = workbook.getSheet("calls"); Row row = calls.getRow(1);
            assertThat(cell(calls, row, "comparisonMatch").getBooleanCellValue()).isFalse();
            assertThat(cell(calls, row, "comparisonDetail").getStringCellValue()).contains("reference curl");
            assertThat(cell(calls, row, "referenceCurlLog").getHyperlink()).isNotNull();
        }
    }

    @Test void invalidReferenceFailsBeforeRequestsAndPreStopSkipsBothRequests() throws Exception {
        Path output = directory.resolve("keep.xlsx"); Files.writeString(output, "keep");
        Path invalid = write(Map.of("host.api.baseUrl", url(base), "host.api.referenceUrl", "relative/path"),
                List.of(), List.of(), List.of(row(1, "/echo", "")));
        var console = new StringWriter();
        assertThat(new RootCommand().execute(console, "run", "--scenario", invalid.toString(), "--output", output.toString())).isEqualTo(2);
        assertThat(Files.readString(output)).isEqualTo("keep"); assertThat(baseCalls).isEmpty(); assertThat(referenceCalls).isEmpty();
        var step = new java.util.LinkedHashMap<>(row(1, "/echo", "")); step.put("preScripts", "stop");
        Path stopped = write(Map.of("host.api.baseUrl", url(base), "host.api.referenceUrl", url(reference)),
                List.of(Map.of("id", "stop", "phase", "PRE", "body", "control.stop('before curl')")), List.of(), List.of(step));
        assertThat(new RootCommand().execute(console, "run", "--scenario", stopped.toString(), "--output", output.toString())).isEqualTo(1);
        assertThat(baseCalls).isEmpty(); assertThat(referenceCalls).isEmpty();
    }

    @Test void headerComparisonNormalizesFinalBlockAndPreservesDuplicateValues() {
        var base = response("HTTP/1.1 100 Continue\r\nX-Ignored: interim\r\n\r\nHTTP/1.1 200 OK: custom reason\r\n" +
                "X-Value: a\r\nX-Value: b\r\nContent-Type: application/json\r\n\r\n", "{\"a\":1}\n");
        var reference = response("HTTP/2 200\ncontent-type: application/json\nx-value: a\nx-value: b\n\n", "{\"a\":1}\n");
        var comparison = ReferenceComparison.compare("http://reference/", base, reference);
        assertThat(comparison.matches()).isTrue(); assertThat(comparison.detail()).isEqualTo("matched");
        var changed = ReferenceComparison.compare("http://reference/", base,
                response("HTTP/1.1 200 OK\r\nX-Value: b\r\nX-Value: a\r\nContent-Type: application/json\r\n", "{ \"a\": 1 }\n"));
        assertThat(changed.headersMatch()).isFalse(); assertThat(changed.responseMatch()).isTrue();
        assertThat(changed.detail()).contains("x-value").doesNotContain("response differs");
    }

    @Test void preScriptRequestChangesAreMirroredWithReferencePrefixAndEncodedQuery() throws Exception {
        var step = new java.util.LinkedHashMap<>(row(1, "/original", "")); step.put("preScripts", "change");
        Path input = write(Map.of("host.api.baseUrl", url(base) + "/base", "host.api.referenceUrl", url(reference) + "/ref"),
                List.of(Map.of("id", "change", "phase", "PRE", "body", "request.path = '/changed?key=%2F'; request.body = 'changed body'")),
                List.of(), List.of(step));
        var results = new ScenarioRunner().run(new ScenarioRunPlanReader().read(input, null), directory.resolve("changed.xlsx"), false);
        assertThat(results).hasSize(1); assertThat(results.getFirst().comparison()).isNotNull();
        assertThat(baseCalls.getFirst()).contains("/changed?key=%2F body=changed body");
        assertThat(referenceCalls.getFirst()).contains("/ref/changed?key=%2F body=changed body");
        assertThat(results.getFirst().comparison().response().logFile()).isNull();
    }

    @Test void omittedReferenceProducesNoComparisonOrReferenceHttpCall() throws Exception {
        Path input = write(Map.of("host.api.baseUrl", url(base)), List.of(), List.of(), List.of(row(1, "/echo", "")));
        var results = new ScenarioRunner().run(new ScenarioRunPlanReader().read(input, null), directory.resolve("plain.xlsx"), false);
        assertThat(results.getFirst().comparison()).isNull(); assertThat(referenceCalls).isEmpty(); assertThat(baseCalls).hasSize(1);
    }

    @Test void invalidRuntimeReferenceTemplateDoesNotSuppressBaseCallsOrScripts() throws Exception {
        var first = new java.util.LinkedHashMap<>(row(1, "/echo", "")); first.put("postScripts", "save");
        Path input = write(Map.of("host.api.baseUrl", url(base), "host.api.referenceUrl", url(reference) + "/${global.missing}"),
                List.of(Map.of("id", "save", "phase", "POST", "body", "global.saved = 'base script ran'")),
                List.of(), List.of(first, row(2, "/echo", "${global.saved}")));
        var results = new ScenarioRunner().run(new ScenarioRunPlanReader().read(input, null), directory.resolve("render-error.xlsx"), false);
        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(result.success()).isTrue(); assertThat(result.comparison().matches()).isFalse();
            assertThat(result.comparison().detail()).contains("reference request:", "missing");
        });
        assertThat(baseCalls).hasSize(2); assertThat(baseCalls.get(1)).contains("body=base script ran");
        assertThat(referenceCalls).isEmpty();
    }

    private static io.github.apiscenariotester.http.CurlResponse response(String headers, String body) {
        return new io.github.apiscenariotester.http.CurlResponse(200, 0, 1, headers, body, "", null);
    }

    @Test void referenceMappingPreservesEmptySuffixVersusTrailingSlash() {
        var scripts = new io.github.apiscenariotester.script.JexlRuntime(List.of());
        var target = ReferenceTarget.validated("http://base/api", "http://reference/v2", scripts);
        var context = new org.apache.commons.jexl3.MapContext();
        var request = new io.github.apiscenariotester.http.CurlRequest("GET", "http://base/api?x=%2F", Map.of(), "", 100, 100);
        assertThat(target.request(request, scripts, context).url()).isEqualTo("http://reference/v2?x=%2F");
        request = new io.github.apiscenariotester.http.CurlRequest("GET", "http://base/api/?x=%2F", Map.of(), "", 100, 100);
        assertThat(target.request(request, scripts, context).url()).isEqualTo("http://reference/v2/?x=%2F");
    }

    @Test void comparisonIgnoresHttpVersusHttpsInHeaderValuesAndResponseText() {
        var base = response("HTTP/1.1 200 OK\r\nLocation: http://example.com/users\r\n" +
                "Link: <http://example.com/a>; rel=next\r\nLink: <https://example.com/b>; rel=prev\r\n",
                "{\"url\":\"http://example.com/users\",\"callback\":\"https://example.com/a\"}\n");
        var reference = response("HTTP/2 200\nlocation: https://example.com/users\n" +
                "link: <https://example.com/a>; rel=next\nlink: <http://example.com/b>; rel=prev\n",
                "{\"url\":\"https://example.com/users\",\"callback\":\"http://example.com/a\"}\n");
        var comparison = ReferenceComparison.compare("https://reference/", base, reference);
        assertThat(comparison.matches()).isTrue(); assertThat(comparison.detail()).isEqualTo("matched");
        assertThat(comparison.url()).isEqualTo("https://reference/");
        assertThat(comparison.response()).isEqualTo(reference);
        var changed = ReferenceComparison.compare("https://reference/", base,
                response(reference.headers().replace("/users", "/other"), reference.body().replace("/users", "/other")));
        assertThat(changed.headersMatch()).isFalse(); assertThat(changed.responseMatch()).isFalse();
        assertThat(changed.detail()).contains("location", "response differs");
    }

    @Test void jsonComparisonUsesPrettyTextThenSchemeNormalization() {
        var base = response("", "{\"url\":\"http://example.com/users\",\"items\":[1,{\"name\":\"한글\"}]}\n");
        var reference = response("", " {\r\n  \"url\" : \"https:\\/\\/example.com/users\",\r\n  \"items\": [ 1, { \"name\": \"한글\" } ]\r\n} ");
        var comparison = ReferenceComparison.compare("https://reference/", base, reference);
        assertThat(comparison.matches()).isTrue(); assertThat(comparison.detail()).isEqualTo("matched");
        assertThat(comparison.response()).isEqualTo(reference);
        var changed = ReferenceComparison.compare("https://reference/", base,
                response("", reference.body().replace("한글", "다른 값")));
        assertThat(changed.responseMatch()).isFalse(); assertThat(changed.detail()).contains("response differs at normalized character");
        assertThat(ReferenceComparison.compare("https://reference/", response("", "plain\n"), response("", "plain\r\n"))
                .responseMatch()).isFalse();
    }

    @Test void multilineRuntimeSkipRulesExcludeHeaderNamesAndJsonPathsButKeepSavedResponses() throws Exception {
        Path config = directory.resolve("skip-runtime.yml");
        Files.writeString(config, "hosts:\n  api:\n    compareSkipHeader: |\n      date\n      X-SERVER\n\n      Content-Length\n" +
                "    compareSkipBody: |\n      $.server\n      $.nested.timestamp\n      $.users[*].updatedAt\n      $.missing\n");
        Path input = write(Map.of("sessions", "2", "host.api.baseUrl", url(base), "host.api.referenceUrl", url(reference),
                "host.api.compareSkipHeader", "unused", "host.api.compareSkipBody", "$.unused"),
                List.of(), List.of(), List.of(row(1, "/skip", "")));
        Path output = directory.resolve("skip.xlsx"); var console = new StringWriter();
        assertThat(new RootCommand().execute(console, "run", "--scenario", input.toString(), "--config", config.toString(),
                "--output", output.toString())).as(console.toString()).isZero();
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet sheet = workbook.getSheet("calls");
            for (int index = 1; index <= 2; index++) {
                Row row = sheet.getRow(index);
                assertThat(cell(sheet, row, "comparisonMatch").getBooleanCellValue()).isTrue();
                assertThat(cell(sheet, row, "responseHeaders").getStringCellValue().toLowerCase()).contains("x-server: base");
                assertThat(cell(sheet, row, "responseBody").getStringCellValue()).contains("\"server\"", "\"base\"", "updatedAt");
                assertThat(cell(sheet, row, "referenceResponseBody").getStringCellValue()).contains("\"server\"", "\"reference\"", "updatedAt");
            }
        }
    }

    @Test void invalidSkipJsonPathIsRejectedBeforeHttpAndOutputRename() throws Exception {
        Path input = write(Map.of("host.api.baseUrl", url(base), "host.api.referenceUrl", url(reference),
                "compareSkipBody", "$.users["), List.of(), List.of(), List.of(row(1, "/skip", "")));
        Path output = directory.resolve("keep-skip.xlsx"); Files.writeString(output, "keep"); var console = new StringWriter();
        assertThat(new RootCommand().execute(console, "validate", "--scenario", input.toString())).isEqualTo(2);
        assertThat(new RootCommand().execute(console, "run", "--scenario", input.toString(), "--output", output.toString())).isEqualTo(2);
        assertThat(baseCalls).isEmpty(); assertThat(referenceCalls).isEmpty(); assertThat(Files.readString(output)).isEqualTo("keep");
        assertThat(console.toString()).contains("compareSkipBody");
    }

    @Test void skipRulesUseCommonDefaultsThenHostOverridesAndRuntimeCanClearThem() throws Exception {
        Path input = write(Map.of("host.api.baseUrl", url(base), "host.api.referenceUrl", url(reference),
                "compareSkipHeader", "Date\nX-Default", "compareSkipBody", "$.default"), List.of(), List.of(), List.of(row(1, "/skip", "")));
        var defaults = new ScenarioRunPlanReader().read(input, null).steps().getFirst().reference().rules();
        assertThat(defaults.skipsHeader("X-Default")).isTrue();
        assertThat(defaults.filterBody("{\"default\":1,\"host\":2}")).isEqualTo("{\"host\":2}");
        input = write(Map.of("host.api.baseUrl", url(base), "host.api.referenceUrl", url(reference),
                "compareSkipHeader", "X-Default", "compareSkipBody", "$.default",
                "host.api.compareSkipHeader", "X-Host", "host.api.compareSkipBody", "$.host"), List.of(), List.of(), List.of(row(1, "/skip", "")));
        var host = new ScenarioRunPlanReader().read(input, null).steps().getFirst().reference().rules();
        assertThat(host.skipsHeader("X-Host")).isTrue(); assertThat(host.skipsHeader("X-Default")).isFalse();
        assertThat(host.filterBody("{\"default\":1,\"host\":2}")).isEqualTo("{\"default\":1}");
        Path config = directory.resolve("clear-rules.yml");
        Files.writeString(config, "hosts:\n  api:\n    compareSkipHeader: ''\n    compareSkipBody: ''\n");
        var cleared = new ScenarioRunPlanReader().read(input, config).steps().getFirst().reference().rules();
        assertThat(cleared.skipsHeader("X-Host")).isFalse();
        assertThat(cleared.filterBody("{\"host\":2}")).isEqualTo("{\"host\":2}");
        assertThat(baseCalls).isEmpty(); assertThat(referenceCalls).isEmpty();
    }

    private Path write(Map<String, String> common, List<Map<String, String>> scripts, List<Map<String, String>> subsets,
            List<Map<String, String>> main) throws Exception {
        Path input = directory.resolve(java.util.UUID.randomUUID() + ".yml");
        new ScenarioYamlCodec().write(new ScenarioDocument(1, common, Map.of(), scripts, subsets, main), input); return input;
    }
    private static Map<String, String> row(int order, String path, String body) {
        return Map.of("order", Integer.toString(order), "name", "step-" + order, "host", "api", "method", "POST", "path", path, "body", body);
    }
    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private static Cell cell(Sheet sheet, Row row, String name) {
        for (Cell header : sheet.getRow(0)) if (header.getStringCellValue().equals(name)) return row.getCell(header.getColumnIndex());
        throw new AssertionError("Missing column: " + name);
    }
}
