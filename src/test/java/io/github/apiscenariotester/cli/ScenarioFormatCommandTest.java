package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioExcelCodec;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioFormatCommandTest {

    @TempDir Path tempDirectory;

    @Test void excelConversionDisplaysMultilineYamlAndPreservesOriginalText() throws Exception {
        String script = "var token = response.headers['x-token'];\n\n" +
                "global['x-token'] = token[0];";
        var expected = new ScenarioDocument(1, Map.of("description", "first\nsecond\n"), Map.of(),
                List.of(Map.of("id", "extract-token", "phase", "POST", "body", script)), List.of(),
                List.of(Map.of("order", "1", "body", "{\n  \"name\": \"user\"\n}\n\n")));
        Path source = tempDirectory.resolve("source.xlsx"), yaml = tempDirectory.resolve("scenario.yml"),
                restored = tempDirectory.resolve("restored.xlsx");
        new ScenarioExcelCodec().write(expected, source);
        var console = new StringWriter();
        assertThat(new RootCommand().execute(console, "convert", "excel", "--input", source.toString(),
                "--output", yaml.toString())).isZero();
        assertThat(Files.readString(yaml)).contains("|-", "|+", "  var token = response.headers['x-token'];")
                .doesNotContain("\\n");
        assertThat(new io.github.apiscenariotester.scenario.ScenarioYamlCodec().read(yaml)).isEqualTo(expected);
        assertThat(new RootCommand().execute(console, "convert", "yaml", "--input", yaml.toString(),
                "--output", restored.toString())).isZero();
        assertThat(new ScenarioExcelCodec().read(restored)).isEqualTo(expected);
    }

    @Test void missingExcelInputReportsCauseStageAndAbsolutePaths() throws Exception {
        Path input = tempDirectory.resolve("login-user-scenario.yml"), output = tempDirectory.resolve("output.yml");
        Files.writeString(output,"keep"); var console = new StringWriter();
        assertThat(new RootCommand().execute(console,"convert","excel","--input",input.toString(),"--output",output.toString())).isEqualTo(2);
        assertThat(console.toString()).contains("stage=read input", "NoSuchFileException", "input="+input.toAbsolutePath(), "output="+output.toAbsolutePath());
        assertThat(Files.readString(output)).isEqualTo("keep");
    }

    @Test void createsMissingOutputDirectoriesForBothConversionDirections() throws Exception {
        var document = new ScenarioDocument(1,Map.of(),Map.of(),List.of(),List.of(),List.of());
        Path input = tempDirectory.resolve("input.xlsx"), yaml = tempDirectory.resolve("new/yaml/scenario.yml"), excel = tempDirectory.resolve("new/excel/scenario.xlsx");
        new ScenarioExcelCodec().write(document,input); var console = new StringWriter();
        assertThat(new RootCommand().execute(console,"convert","excel","--input",input.toString(),"--output",yaml.toString())).isZero();
        assertThat(new RootCommand().execute(console,"convert","yaml","--input",yaml.toString(),"--output",excel.toString())).isZero();
        assertThat(new ScenarioExcelCodec().read(excel)).isEqualTo(document);
    }

    @Test void outputPreparationErrorIdentifiesStageAndPath() throws Exception {
        Path input = tempDirectory.resolve("input.xlsx"), output = Files.createDirectory(tempDirectory.resolve("login-user-scenario.yml"));
        new ScenarioExcelCodec().write(new ScenarioDocument(1,Map.of(),Map.of(),List.of(),List.of(),List.of()),input);
        var console = new StringWriter();
        assertThat(new RootCommand().execute(console,"convert","excel","--input",input.toString(),"--output",output.toString())).isEqualTo(2);
        assertThat(console.toString()).contains("stage=prepare output", "output="+output.toAbsolutePath(), "Output is not a regular file");
        assertThat(output).isDirectory();
    }

    @Test void renamesBothExistingConversionOutputsWithTimestampSuffixes() throws Exception {
        var expected = new ScenarioDocument(1,Map.of(),Map.of(),List.of(),List.of(),List.of());
        Path source = tempDirectory.resolve("source.xlsx"), yaml = tempDirectory.resolve("scenario.yml"), excel = tempDirectory.resolve("restored.xlsx");
        new ScenarioExcelCodec().write(expected,source);
        Files.writeString(yaml,"old yaml"); Files.writeString(excel,"old excel");
        var console = new StringWriter();
        assertThat(new RootCommand().execute(console,"convert","excel","--input",source.toString(),"--output",yaml.toString())).isZero();
        assertThat(new RootCommand().execute(console,"convert","yaml","--input",yaml.toString(),"--output",excel.toString())).isZero();
        assertThat(new ScenarioExcelCodec().read(excel)).isEqualTo(expected);
        try (var files = Files.list(tempDirectory)) {
            var backups = files.filter(file -> file.getFileName().toString().matches("(scenario|restored)_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?\\.(yml|xlsx)")).toList();
            assertThat(backups).hasSize(2);
            for (Path backup : backups) {
                String name = backup.getFileName().toString();
                int start = name.indexOf('_')+1;
                java.time.LocalDateTime.parse(name.substring(start,start+19),java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS"));
                assertThat(Files.readString(backup)).isEqualTo(backup.toString().endsWith(".yml") ? "old yaml" : "old excel");
            }
        }
        assertThat(console.toString()).contains("Renamed existing output:");
    }

    @Test void invalidInputLeavesExistingOutputAndDirectoriesUntouched() throws Exception {
        Path input = tempDirectory.resolve("invalid.yml"), output = tempDirectory.resolve("output.xlsx");
        Files.writeString(input,"not a valid scenario"); Files.writeString(output,"keep");
        assertThat(new RootCommand().execute(new StringWriter(),"convert","yaml","--input",input.toString(),"--output",output.toString())).isEqualTo(2);
        assertThat(Files.readString(output)).isEqualTo("keep");
        Path valid = tempDirectory.resolve("valid.yml");
        new io.github.apiscenariotester.scenario.ScenarioYamlCodec().write(new ScenarioDocument(1,Map.of(),Map.of(),List.of(),List.of(),List.of()),valid);
        Path folder = Files.createDirectory(tempDirectory.resolve("folder.xlsx")); Files.writeString(folder.resolve("child"),"keep");
        assertThat(new RootCommand().execute(new StringWriter(),"convert","yaml","--input",valid.toString(),"--output",folder.toString())).isEqualTo(2);
        assertThat(Files.readString(folder.resolve("child"))).isEqualTo("keep");
        try (var files = Files.list(tempDirectory)) { assertThat(files.noneMatch(file -> file.getFileName().toString().matches(".*_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?(\\..*)?"))).isTrue(); }
    }

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
