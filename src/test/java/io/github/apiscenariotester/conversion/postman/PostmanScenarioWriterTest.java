package io.github.apiscenariotester.conversion.postman;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.ConvertedScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostmanScenarioWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesConvertedScenarioAndWarnings() throws Exception {
        ConversionResult conversion = new ConversionResult(
                List.of(new ConvertedScenario(
                        1,
                        "Get user",
                        "GET",
                        "https://api.example.com",
                        "/users/${global.userId}",
                        "{\"Accept\":\"application/json\"}",
                        "")),
                List.of("Get user test script is not converted"));
        Path output = tempDirectory.resolve("scenario.xlsx");

        new PostmanScenarioWriter().write(conversion, output);

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Row host = workbook.getSheet("common").getRow(7);
            assertThat(host.getCell(0).getStringCellValue()).isEqualTo("host.postman.baseUrl");
            assertThat(host.getCell(1).getStringCellValue()).isEqualTo("https://api.example.com");

            Row scenario = workbook.getSheet("main_scenarios").getRow(1);
            assertThat(scenario.getCell(0).getNumericCellValue()).isEqualTo(1);
            assertThat(scenario.getCell(1).getStringCellValue()).isEqualTo("Get user");
            assertThat(scenario.getCell(2).getBooleanCellValue()).isTrue();
            assertThat(scenario.getCell(3).getStringCellValue()).isEqualTo("postman");
            assertThat(scenario.getCell(4).getStringCellValue()).isEqualTo("GET");
            assertThat(scenario.getCell(5).getStringCellValue())
                    .isEqualTo("/users/${global.userId}");
            assertThat(scenario.getCell(8).getStringCellValue())
                    .isEqualTo("{\"Accept\":\"application/json\"}");
            assertThat(scenario.getCell(10).getStringCellValue()).isEqualTo("200-299");
        }

        Path warnings = tempDirectory.resolve("scenario.warnings.yml");
        assertThat(warnings).exists();
        assertThat(Files.readString(warnings))
                .contains("warnings:")
                .contains("Get user test script is not converted");
    }

    @Test
    void omitsWarningFileWhenThereAreNoWarnings() throws Exception {
        ConversionResult conversion = new ConversionResult(
                List.of(new ConvertedScenario(1, "Ping", "GET", "https://api.example.com", "/ping", "{}", "")),
                List.of());
        Path output = tempDirectory.resolve("scenario.xlsx");

        new PostmanScenarioWriter().write(conversion, output);

        assertThat(tempDirectory.resolve("scenario.warnings.yml")).doesNotExist();
    }
}
