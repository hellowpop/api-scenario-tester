package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateCommandTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsTemplateAtRequestedPath() throws Exception {
        Path output = tempDirectory.resolve("scenario.xlsx");
        StringWriter console = new StringWriter();

        int exitCode = new RootCommand().execute(
                console, "template", "--output", output.toString());

        assertThat(exitCode).isZero();
        assertThat(output).exists();
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertThat(workbook.getSheet("main_scenarios")).isNotNull();
        }
    }

    @Test
    void returnsInputErrorWhenOutputAlreadyExists() throws Exception {
        Path output = tempDirectory.resolve("scenario.xlsx");
        Files.writeString(output, "existing");
        StringWriter console = new StringWriter();

        int exitCode = new RootCommand().execute(
                console, "template", "--output", output.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.toString()).contains("already exists");
        assertThat(Files.readString(output)).isEqualTo("existing");
    }
}
