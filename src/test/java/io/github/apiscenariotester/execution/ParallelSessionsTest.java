package io.github.apiscenariotester.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.apiscenariotester.cli.RootCommand;
import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioYamlCodec;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParallelSessionsTest {
    @TempDir Path directory;

    @Test void concurrentSessionsKeepTokensCookiesIterationsAndDebugLinksIsolated() throws Exception {
        var barrier = new CyclicBarrier(2);
        var checked = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String session = path.substring(path.lastIndexOf('/') + 1);
                int status = 200;
                if (path.startsWith("/login")) {
                    try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { status = 500; }
                    exchange.getResponseHeaders().add("X-Token", "token-" + session);
                    exchange.getResponseHeaders().add("Set-Cookie", "session=" + session + "; Path=/");
                } else {
                    checked.add(session + ":" + exchange.getRequestHeaders().getFirst("X-Token") +
                            ":" + exchange.getRequestHeaders().getFirst("Cookie"));
                }
                exchange.sendResponseHeaders(status, -1); exchange.close();
            });
            server.start();
            try {
                var login = Map.of("subsetId", "login", "order", "1", "name", "login", "method", "GET",
                        "path", url(server, "/login/${scenario.session}"), "postScripts", "token");
                var script = Map.of("id", "token", "phase", "POST", "body", "global.token = response.headers['x-token'][0]");
                var use = Map.of("order", "2", "name", "use", "method", "GET", "path", url(server, "/use/${scenario.session}"),
                        "headers", "{\"X-Token\":\"${global.token}\"}");
                Path input = write("isolated", Map.of("sessions", "2", "iterations", "2"), List.of(script), List.of(login),
                        List.of(Map.of("order", "1", "name", "login", "method", "SUBSET", "path", "login"), use));
                Path output = directory.resolve("results.xlsx");
                var console = new StringWriter();
                assertThat(new RootCommand().execute(console, "run", "--scenario", input.toString(), "--output", output.toString(), "--debug"))
                        .as(console.toString()).isZero();
                assertThat(checked).containsExactlyInAnyOrder("1:token-1:session=1", "2:token-2:session=2",
                        "1:token-1:session=1", "2:token-2:session=2");
                try (var files = Files.list(directory.resolve("curl"))) { assertThat(files.count()).isEqualTo(8); }
                try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
                    var calls = workbook.getSheet("calls");
                    assertThat(calls.getLastRowNum()).isEqualTo(8);
                    int sessionColumn = -1;
                    for (var cell : calls.getRow(0)) if (cell.getStringCellValue().equals("session")) sessionColumn = cell.getColumnIndex();
                    assertThat(sessionColumn).isZero();
                    for (int row = 1; row <= 8; row++) {
                        assertThat(calls.getRow(row).getCell(sessionColumn).getNumericCellValue()).isEqualTo(row <= 4 ? 1 : 2);
                        String link = calls.getRow(row).getCell(11).getHyperlink().getAddress();
                        assertThat(directory.resolve(link)).isRegularFile();
                    }
                }
            } finally { server.stop(0); }
        }
    }

    @Test void failurePolicyAndScriptStopOnlyAffectTheirSession() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/fail/1") ? 500 : 200, -1);
                exchange.close();
            }); server.start();
            try {
                for (boolean continuing : List.of(false, true)) {
                    Path input = write("failure-" + continuing, Map.of("sessions", "2", "continueOnFailure", Boolean.toString(continuing)),
                            List.of(), List.of(), List.of(row(server, 1, "/ok"), row(server, 2, "/fail/${scenario.session}"), row(server, 3, "/tail")));
                    var results = new ScenarioRunner().run(new ScenarioRunPlanReader().read(input, null), directory.resolve("failure.xlsx"), false);
                    assertThat(results).hasSize(continuing ? 6 : 5);
                    assertThat(results.stream().filter(CallResult::success).count()).isEqualTo(continuing ? 5 : 4);
                }
                var first = new java.util.LinkedHashMap<>(row(server, 1, "/ok")); first.put("postScripts", "stop");
                Path input = write("stop", Map.of("sessions", "2", "continueOnFailure", "true"),
                        List.of(Map.of("id", "stop", "phase", "POST", "body", "if (scenario.session == 1) control.stop('session one')")),
                        List.of(), List.of(first, row(server, 2, "/tail")));
                var results = new ScenarioRunner().run(new ScenarioRunPlanReader().read(input, null), directory.resolve("stop.xlsx"), false);
                assertThat(results).hasSize(3);
                assertThat(results.getFirst().error()).contains("session one");
            } finally { server.stop(0); }
        }
    }

    @Test void interruptionStopsAllActiveCurlWorkersAndFinishesCleanup() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor(); var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { exchange.sendResponseHeaders(200, -1); } finally { exchange.close(); }
            }); server.start();
            try {
                Path input = write("interrupt", Map.of("sessions", "2"), List.of(), List.of(), List.of(row(server, 1, "/slow")));
                var plan = new ScenarioRunPlanReader().read(input, null);
                var run = caller.submit(() -> {
                    try { new ScenarioRunner().run(plan, directory.resolve("interrupt.xlsx"), true); }
                    catch (Throwable e) { failure.set(e); }
                    finally { finished.countDown(); }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                run.cancel(true);
                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isInstanceOf(InterruptedException.class);
                try (var files = Files.list(directory.resolve("curl"))) {
                    var logs = files.toList();
                    assertThat(logs).hasSize(2);
                    for (Path log : logs) assertThat(Files.readString(log)).contains("Interrupted", "EXIT CODE: -1");
                }
            } finally { release.countDown(); server.stop(0); }
        }
    }

    private Path write(String name, Map<String, String> common, List<Map<String, String>> scripts,
            List<Map<String, String>> subsets, List<Map<String, String>> main) throws Exception {
        Path input = directory.resolve(name + ".yml");
        new ScenarioYamlCodec().write(new ScenarioDocument(1, common, Map.of(), scripts, subsets, main), input);
        return input;
    }
    private static String url(HttpServer server, String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    private static Map<String, String> row(HttpServer server, int order, String path) {
        return Map.of("order", Integer.toString(order), "name", "step-" + order, "method", "GET", "path", url(server, path));
    }
}
