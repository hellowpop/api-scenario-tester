package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioExcelCodec;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioFormatCommandTest {

    @TempDir Path tempDirectory;

    @Test
    void convertsExcelToYamlAndYamlBackToExcel() throws Exception {
        ScenarioDocument expected = new ScenarioDocument(
                1,
                Map.of("sessions", "1"),
                Map.of("output", "results.yml"),
                List.of(), List.of(),
                List.of(Map.of("order", "1", "name", "health", "enabled", "true", "host", "api", "method", "GET", "path", "/health", "expectedStatus", "200-299")));
        Path sourceExcel = tempDirectory.resolve("source.xlsx");
        new ScenarioExcelCodec().write(expected, sourceExcel);
        Path yaml = tempDirectory.resolve("scenario.yml");
        Path restoredExcel = tempDirectory.resolve("restored.xlsx");
        StringWriter console = new StringWriter();

        int toYaml = new RootCommand().execute(console, "convert", "excel", "--input", sourceExcel.toString(), "--output", yaml.toString());
        int toExcel = new RootCommand().execute(console, "convert", "yaml", "--input", yaml.toString(), "--output", restoredExcel.toString());

        assertThat(toYaml).isZero();
        assertThat(toExcel).isZero();
        assertThat(new ScenarioExcelCodec().read(restoredExcel)).isEqualTo(expected);
        assertThat(console.toString()).contains("Converted scenario");
    }
}
