package io.github.apiscenariotester.execution;

import io.github.apiscenariotester.http.CurlHttpClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import io.github.apiscenariotester.script.ScriptContext;
import io.github.apiscenariotester.script.ExecutionControl;
import io.github.apiscenariotester.http.CurlResponse;

/** Main and expanded subset HTTP steps share the same curl client and debug logging policy. */
public final class ScenarioRunner {
    public List<CallResult> run(ScenarioRunPlan plan, Path output, boolean debug) throws IOException, InterruptedException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        List<CallResult> results = new ArrayList<>();
        var globals = new LinkedHashMap<String,Object>(plan.globals());
        var executor = new LinkedHashMap<String,Object>();
        try (var client = new CurlHttpClient(plan.curlExecutable(), debug ? parent.resolve("curl") : null)) {
            boolean first = true;
            for (int iteration = 1; iteration <= plan.iterations(); iteration++) {
                for (var planned : plan.steps()) {
                    if (!first) Thread.sleep(plan.waitPolicy().nextDelayMillis());
                    first = false;
                    var step = planned;
                    CurlResponse response = new CurlResponse(0, -1, 0, "", "", "", null);
                    String error = "";
                    var context = new ScriptContext();
                    var request = plan.scripts().request(step.request());
                    var control = new ExecutionControl();
                    context.bind("global", globals); context.bind("executor", executor); context.bind("request", request);
                    context.bind("scenario", Map.of("name", step.name(), "order", step.order(), "iteration", iteration));
                    context.bind("control", control);
                    try {
                        plan.scripts().execute("PRE", step.scriptIds().getOrDefault("PRE", List.of()), context);
                        if (!control.isStopped()) {
                            step = step.withRequest(plan.scripts().renderRequest(step.request(), request, context));
                            context.bind("request", plan.scripts().request(step.request()));
                            response = client.execute(step.request());
                            error = response.error();
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
                    results.add(new CallResult(iteration, step, response, success, error));
                    if (control.isStopped() || (!success && !plan.continueOnFailure())) return List.copyOf(results);
                }
            }
        }
        return List.copyOf(results);
    }
}
