package io.github.apiscenariotester.input.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioTemplateWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesRequiredSheetsHeadersAndDefaults() throws Exception {
        Path output = tempDirectory.resolve("scenario.xlsx");

        new ScenarioTemplateWriter().write(output);

        try (InputStream input = Files.newInputStream(output);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertThat(sheetNames(workbook)).containsExactly(
                    "common", "result_format", "scripts", "sub_scenarios", "main_scenarios");
            assertHeaders(workbook.getSheet("common"), "key", "value");
            assertRow(workbook.getSheet("common"), 1, "sessions", "1");
            assertRow(workbook.getSheet("common"), 2, "iterations", "1");
            assertRow(workbook.getSheet("common"), 3, "waitPattern", "FIXED");
            assertRow(workbook.getSheet("common"), 4, "waitMinMs", "0");
            assertRow(workbook.getSheet("common"), 5, "waitMaxMs", "0");
            assertRow(workbook.getSheet("common"), 6, "continueOnFailure", "false");

            assertHeaders(workbook.getSheet("result_format"), "key", "value");
            assertRow(workbook.getSheet("result_format"), 1, "output", "results.xlsx");
            assertRow(workbook.getSheet("result_format"), 2, "includeCallDetails", "true");
            assertRow(workbook.getSheet("result_format"), 3, "percentiles", "50,90,95,99");
            assertRow(workbook.getSheet("result_format"), 4, "trimPercent", "5");

            assertHeaders(workbook.getSheet("scripts"), "id", "phase", "body");
            assertHeaders(
                    workbook.getSheet("sub_scenarios"),
                    "subsetId", "order", "name", "host", "method", "path", "preScripts",
                    "headers", "body", "expectedStatus", "expectedMaxMs", "filterScripts",
                    "validationScripts", "postScripts");
            assertHeaders(
                    workbook.getSheet("main_scenarios"),
                    "order", "name", "enabled", "host", "method", "path", "preSubsets",
                    "preScripts", "headers", "body", "expectedStatus", "expectedMaxMs",
                    "filterScripts", "validationScripts", "postScripts");
        }
    }

    @Test
    void refusesToOverwriteExistingFile() throws Exception {
        Path output = tempDirectory.resolve("scenario.xlsx");
        Files.writeString(output, "existing", StandardOpenOption.CREATE_NEW);

        assertThatThrownBy(() -> new ScenarioTemplateWriter().write(output))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(Files.readString(output)).isEqualTo("existing");
    }

    private static List<String> sheetNames(XSSFWorkbook workbook) {
        return java.util.stream.IntStream.range(0, workbook.getNumberOfSheets())
                .mapToObj(workbook::getSheetName)
                .toList();
    }

    private static void assertHeaders(Sheet sheet, String... headers) {
        assertThat(sheet).isNotNull();
        Row row = sheet.getRow(0);
        assertThat(row).isNotNull();
        assertThat(java.util.stream.IntStream.range(0, headers.length)
                        .mapToObj(index -> row.getCell(index).getStringCellValue()))
                .containsExactly(headers);
    }

    private static void assertRow(Sheet sheet, int rowIndex, String key, String value) {
        Row row = sheet.getRow(rowIndex);
        assertThat(row.getCell(0).getStringCellValue()).isEqualTo(key);
        assertThat(row.getCell(1).getStringCellValue()).isEqualTo(value);
    }
}
