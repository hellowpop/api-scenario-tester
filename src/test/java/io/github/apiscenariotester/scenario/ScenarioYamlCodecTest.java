package io.github.apiscenariotester.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioYamlCodecTest {

    @TempDir Path tempDirectory;

    @Test
    void writesAndReadsVersionedScenarioDocument() throws Exception {
        ScenarioDocument expected = new ScenarioDocument(
                1,
                linkedMap("sessions", "2", "iterations", "3"),
                linkedMap("output", "results.yml", "percentiles", "50,95"),
                List.of(Map.of("id", "save-token", "phase", "POST", "body", "executor.token = response.body")),
                List.of(),
                List.of(linkedMap("order", "1", "name", "health", "enabled", "true", "method", "GET", "path", "/health")));
        Path yaml = tempDirectory.resolve("scenario.yml");
        ScenarioYamlCodec codec = new ScenarioYamlCodec();

        codec.write(expected, yaml);
        ScenarioDocument actual = codec.read(yaml);

        assertThat(actual).isEqualTo(expected);
        assertThat(java.nio.file.Files.readString(yaml))
                .contains("version: 1", "common:", "resultFormat:", "mainScenarios:");
    }

    private static Map<String, String> linkedMap(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }
}
