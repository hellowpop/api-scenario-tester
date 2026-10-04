package io.github.apiscenariotester.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.apiscenariotester.input.excel.ScenarioTemplateWriter;

class ScenarioExcelCodecTest {

    @TempDir Path tempDirectory;

    @Test
    void writesAndReadsAllScenarioSections() throws Exception {
        ScenarioDocument expected = new ScenarioDocument(
                1,
                map("sessions", "2", "iterations", "4", "waitPattern", "FIXED", "waitMinMs", "10", "waitMaxMs", "10", "continueOnFailure", "false", "host.api.baseUrl", "https://api.example.com"),
                map("output", "results.yml", "includeCallDetails", "true", "percentiles", "50,95", "trimPercent", "5"),
                List.of(map("id", "validate", "phase", "VALIDATE", "body", "response.status == 200")),
                List.of(map("subsetId", "login", "order", "1", "name", "login", "host", "api", "method", "POST", "path", "/login", "expectedStatus", "200-299")),
                List.of(map("order", "1", "name", "health", "enabled", "true", "host", "api", "method", "GET", "path", "/health", "expectedStatus", "200-299")));
        Path excel = tempDirectory.resolve("scenario.xlsx");
        ScenarioExcelCodec codec = new ScenarioExcelCodec();

        codec.write(expected, excel);
        ScenarioDocument actual = codec.read(excel);

        assertThat(actual).isEqualTo(expected);
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(excel))) {
            assertThat(workbook.getSheet("main_scenarios").getRow(1).getCell(1).getStringCellValue()).isEqualTo("health");
        }
    }

    @Test
    void normalizesBooleanCellsToLowercaseYamlValues() throws Exception {
        Path excel = tempDirectory.resolve("boolean.xlsx");
        new ScenarioTemplateWriter().write(excel);
        XSSFWorkbook workbook;
        try (var input = Files.newInputStream(excel)) {
            workbook = new XSSFWorkbook(input);
        }
        try (workbook; var output = Files.newOutputStream(excel, StandardOpenOption.TRUNCATE_EXISTING)) {
            var row = workbook.getSheet("main_scenarios").createRow(1);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue("health");
            row.createCell(2).setCellValue(true);
            workbook.write(output);
        }

        ScenarioDocument document = new ScenarioExcelCodec().read(excel);

        assertThat(document.mainScenarios().getFirst()).containsEntry("enabled", "true");
    }

    private static Map<String, String> map(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }
}
