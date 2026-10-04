package io.github.apiscenariotester.conversion.jmeter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.ConvertedScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JMeterScenarioWriterTest {

    @TempDir Path tempDirectory;

    @Test
    void writesCommonSettingsScenarioAndWarnings() throws Exception {
        ConversionResult result = new ConversionResult(
                List.of(new ConvertedScenario(1, "Ping", "GET", "https://api.example.com", "/ping", "{}", "")),
                List.of("ResponseAssertion is not converted: success"),
                Map.of("sessions", "4", "iterations", "5", "waitPattern", "FIXED", "waitMinMs", "250", "waitMaxMs", "250"));
        Path output = tempDirectory.resolve("scenario.xlsx");

        new JMeterScenarioWriter().write(result, output);

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet common = workbook.getSheet("common");
            assertThat(common.getRow(1).getCell(1).getStringCellValue()).isEqualTo("4");
            assertThat(common.getRow(2).getCell(1).getStringCellValue()).isEqualTo("5");
            assertThat(common.getRow(3).getCell(1).getStringCellValue()).isEqualTo("FIXED");
            assertThat(common.getRow(7).getCell(0).getStringCellValue()).isEqualTo("host.jmeter.baseUrl");
            assertThat(workbook.getSheet("main_scenarios").getRow(1).getCell(3).getStringCellValue()).isEqualTo("jmeter");
        }
        assertThat(Files.readString(tempDirectory.resolve("scenario.warnings.yml")))
                .contains("ResponseAssertion is not converted: success");
    }
}
