package io.github.apiscenariotester.execution;

import io.github.apiscenariotester.http.CurlHttpClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import io.github.apiscenariotester.script.ScriptContext;
import io.github.apiscenariotester.script.ExecutionControl;
import io.github.apiscenariotester.http.CurlResponse;

/** Main and expanded subset HTTP steps share the same curl client and debug logging policy. */
public final class ScenarioRunner {
    public List<CallResult> run(ScenarioRunPlan plan, Path output, boolean debug) throws IOException, InterruptedException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        if (plan.steps().isEmpty()) return List.of();
        if (plan.sessions() == 1) return runSession(plan, parent, debug, 1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = new ExecutorCompletionService<List<CallResult>>(workers);
            try {
                for (int session = 1; session <= plan.sessions(); session++) {
                    final int id = session;
                    completion.submit(() -> runSession(plan, parent, debug, id));
                }
                List<CallResult> results = new ArrayList<>();
                for (int session = 0; session < plan.sessions(); session++) results.addAll(completion.take().get());
                // Stable sorting preserves each session's step and iteration order.
                results.sort(Comparator.comparingInt(CallResult::session));
                return List.copyOf(results);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof InterruptedException interrupted) throw interrupted;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IOException("Session execution failed", cause);
            } finally {
                // Interrupt outstanding curl processes before close waits for worker cleanup.
                workers.shutdownNow();
            }
        }
    }

    private List<CallResult> runSession(ScenarioRunPlan plan, Path parent, boolean debug, int session)
            throws IOException, InterruptedException {
        List<CallResult> results = new ArrayList<>();
        var globals = new LinkedHashMap<String,Object>(plan.globals());
        var executor = new LinkedHashMap<String,Object>();
        try (var client = new CurlHttpClient(plan.curlExecutable(), debug ? parent.resolve("curl") : null);
                var referenceClient = plan.steps().stream().anyMatch(step -> step.reference() != null)
                        ? new CurlHttpClient(plan.curlExecutable(), debug ? parent.resolve("curl") : null) : null) {
            boolean first = true;
            for (int iteration = 1; iteration <= plan.iterations(); iteration++) {
                for (var planned : plan.steps()) {
                    if (!first) Thread.sleep(plan.waitPolicy().nextDelayMillis());
                    first = false;
                    var step = planned;
                    CurlResponse response = new CurlResponse(0, -1, 0, "", "", "", null);
                    String error = "";
                    ReferenceComparison comparison = null;
                    var context = new ScriptContext();
                    var request = plan.scripts().request(step.request());
                    var control = new ExecutionControl();
                    context.bind("global", globals); context.bind("executor", executor); context.bind("request", request);
                    context.bind("scenario", Map.of("name", step.name(), "order", step.order(), "iteration", iteration, "session", session));
                    context.bind("control", control);
                    try {
                        plan.scripts().execute("PRE", step.scriptIds().getOrDefault("PRE", List.of()), context);
                        if (!control.isStopped()) {
                            step = step.withRequest(plan.scripts().renderRequest(step.request(), request, context));
                            context.bind("request", plan.scripts().request(step.request()));
                            response = client.execute(step.request());
                            error = response.error();
                            if (step.reference() != null) {
                                try {
                                    var referenceRequest = step.reference().request(step.request(), plan.scripts(), context);
                                    comparison = ReferenceComparison.compare(referenceRequest.url(), response,
                                            referenceClient.execute(referenceRequest), step.reference().rules());
                                } catch (RuntimeException exception) {
                                    comparison = ReferenceComparison.failure(step.reference().referenceUrl(), exception.getMessage());
                                }
                            }
                            context.bind("response", plan.scripts().response(response));
                            plan.scripts().execute("FILTER", step.scriptIds().getOrDefault("FILTER", List.of()), context);
                            plan.scripts().execute("VALIDATE", step.scriptIds().getOrDefault("VALIDATE", List.of()), context);
                            plan.scripts().execute("POST", step.scriptIds().getOrDefault("POST", List.of()), context);
                        }
                    } catch (RuntimeException exception) {
                        error = (error.isEmpty() ? "" : error + "; ") + exception.getMessage();
                    }
                    if (control.isStopped()) error = (error.isEmpty() ? "" : error + "; ") + "execution stopped: " + control.getReason();
                    if (error.isEmpty() && (response.status() < step.statusMin() || response.status() > step.statusMax()))
                        error = "expected status " + step.statusMin() + "-" + step.statusMax() + " but received " + response.status();
                    if (error.isEmpty() && step.expectedMaxMs() != null && response.elapsedMs() > step.expectedMaxMs())
                        error = "response time exceeded " + step.expectedMaxMs() + " ms";
                    boolean success = response.exitCode() == 0 && error.isEmpty();
                    results.add(new CallResult(session, iteration, step, response, success, error, comparison));
                    if (control.isStopped() || (!success && !plan.continueOnFailure())) return List.copyOf(results);
                }
            }
        }
        return List.copyOf(results);
    }
}
