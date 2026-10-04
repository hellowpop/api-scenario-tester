package io.github.apiscenariotester.conversion;

import java.util.List;
import java.util.Map;

public record ConversionResult(
        List<ConvertedScenario> scenarios,
        List<String> warnings,
        Map<String, String> commonValues) {

    public ConversionResult(List<ConvertedScenario> scenarios, List<String> warnings) {
        this(scenarios, warnings, Map.of());
    }

    public ConversionResult {
        scenarios = List.copyOf(scenarios);
        warnings = List.copyOf(warnings);
        commonValues = Map.copyOf(commonValues);
    }
}
