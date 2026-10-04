package io.github.apiscenariotester.execution;

import io.github.apiscenariotester.http.CurlResponse;

public record CallResult(int iteration, ScenarioRunPlan.Step step, CurlResponse response, boolean success, String error) { }
