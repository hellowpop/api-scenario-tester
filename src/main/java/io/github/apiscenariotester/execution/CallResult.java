package io.github.apiscenariotester.execution;

import io.github.apiscenariotester.http.CurlResponse;

public record CallResult(int session, int iteration, ScenarioRunPlan.Step step, CurlResponse response,
        boolean success, String error, ReferenceComparison comparison) { }
