package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostmanCommandTest {

    @TempDir
    Path tempDirectory;

    @Test
    void convertsPostmanCollectionFromCli() throws Exception {
        Path input = writeCollection();
        Path output = tempDirectory.resolve("scenario.xlsx");
        StringWriter console = new StringWriter();

        int exitCode = new RootCommand().execute(
                console,
                "convert", "postman",
                "--input", input.toString(),
                "--output", output.toString());

        assertThat(exitCode).isZero();
        assertThat(console.toString()).contains("Converted 1 request");
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertThat(workbook.getSheet("main_scenarios").getRow(1).getCell(1).getStringCellValue())
                    .isEqualTo("Health check");
        }
    }

    @Test
    void returnsInputErrorForMalformedCollection() throws Exception {
        Path input = tempDirectory.resolve("invalid.json");
        Files.writeString(input, "not-json");
        Path output = tempDirectory.resolve("scenario.xlsx");
        StringWriter console = new StringWriter();

        int exitCode = new RootCommand().execute(
                console,
                "convert", "postman",
                "--input", input.toString(),
                "--output", output.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.toString()).contains("Unable to convert Postman collection");
        assertThat(output).doesNotExist();
    }

    private Path writeCollection() throws Exception {
        Path input = tempDirectory.resolve("collection.json");
        Files.writeString(input, """
                {
                  "info": {"name": "CLI sample"},
                  "item": [{
                    "name": "Health check",
                    "request": {"method": "GET", "url": {"raw": "https://api.example.com/health"}}
                  }]
                }
                """);
        return input;
    }
}
