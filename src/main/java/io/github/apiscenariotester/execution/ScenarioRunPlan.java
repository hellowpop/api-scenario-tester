package io.github.apiscenariotester.execution;

import io.github.apiscenariotester.http.CurlRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import io.github.apiscenariotester.script.JexlRuntime;

public record ScenarioRunPlan(List<Step> steps, int iterations, boolean continueOnFailure,
        WaitPolicy waitPolicy, String curlExecutable, Path output, double trimPercent, List<Integer> percentiles,
        Map<String, String> globals, JexlRuntime scripts) {
    public ScenarioRunPlan { steps = List.copyOf(steps); percentiles = List.copyOf(percentiles); globals = Map.copyOf(globals); }

    public record Step(int order, String name, CurlRequest request, int statusMin, int statusMax,
            Long expectedMaxMs, Map<String, List<String>> scriptIds) {
        public Step { scriptIds = Map.copyOf(scriptIds); }
        public Step withRequest(CurlRequest value) { return new Step(order, name, value, statusMin, statusMax, expectedMaxMs, scriptIds); }
    }
}
