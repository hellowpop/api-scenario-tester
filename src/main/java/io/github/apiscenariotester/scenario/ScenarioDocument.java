package io.github.apiscenariotester.scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ScenarioDocument(
        int version,
        Map<String, String> common,
        Map<String, String> resultFormat,
        List<Map<String, String>> scripts,
        List<Map<String, String>> subScenarios,
        List<Map<String, String>> mainScenarios) {

    public ScenarioDocument {
        if (version != 1) throw new IllegalArgumentException("unsupported scenario version: " + version);
        common = copyMap(common);
        resultFormat = copyMap(resultFormat);
        scripts = copyRows(scripts);
        subScenarios = copyRows(subScenarios);
        mainScenarios = copyRows(mainScenarios);
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static List<Map<String, String>> copyRows(List<Map<String, String>> rows) {
        return rows.stream().map(ScenarioDocument::copyMap).toList();
    }
}
