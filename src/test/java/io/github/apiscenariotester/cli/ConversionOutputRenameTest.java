package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversionOutputRenameTest {
    @TempDir Path directory;

    @Test void postmanAndJmeterPreserveWorkbookAndWarningFiles() throws Exception {
        for (String command : List.of("postman","jmeter")) {
            Path output = directory.resolve(command+".xlsx"), warnings = directory.resolve(command+".warnings.yml");
            Files.writeString(output,"old workbook"); Files.writeString(warnings,"old warnings");
            Path input = Path.of(command.equals("postman") ? "samples/postman/sample-collection.json" : "samples/jmeter/sample-test-plan.jmx");
            var console = new StringWriter();
            assertThat(new RootCommand().execute(console,"convert",command,"--input",input.toString(),"--output",output.toString())).isZero();
            try (var workbook = new XSSFWorkbook(output.toFile())) { assertThat(workbook.getSheet("main_scenarios").getLastRowNum()).isGreaterThan(0); }
            assertThat(Files.readString(warnings)).contains("warnings:");
            try (var files = Files.list(directory)) {
                var backups = files.filter(file -> file.getFileName().toString().matches(command+"(\\.warnings)?_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?\\.(xlsx|yml)")).toList();
                assertThat(backups).hasSize(2);
                for (Path backup : backups) {
                    String name = backup.getFileName().toString();
                    int start = name.indexOf('_')+1;
                    java.time.LocalDateTime.parse(name.substring(start,start+19),java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS"));
                    assertThat(Files.readString(backup)).isEqualTo(backup.toString().endsWith(".xlsx") ? "old workbook" : "old warnings");
                }
            }
        }
    }
    @Test void staleWarningFileIsArchivedWhenNewConversionHasNoWarnings() throws Exception {
        Path input = directory.resolve("collection.json"), output = directory.resolve("scenario.xlsx"), warnings = directory.resolve("scenario.warnings.yml");
        Files.writeString(input,"{\"info\":{\"name\":\"empty\"},\"item\":[]}"); Files.writeString(warnings,"stale warnings");
        assertThat(new RootCommand().execute(new StringWriter(),"convert","postman","--input",input.toString(),"--output",output.toString())).isZero();
        assertThat(output).exists(); assertThat(warnings).doesNotExist();
        try (var files = Files.list(directory)) {
            var backups = files.filter(file -> file.getFileName().toString().matches("scenario\\.warnings_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?\\.yml")).toList();
            assertThat(backups).hasSize(1); assertThat(Files.readString(backups.getFirst())).isEqualTo("stale warnings");
        }
    }
    @Test void warningDirectoryIsRejectedBeforeMovingExistingWorkbook() throws Exception {
        Path output = directory.resolve("scenario.xlsx"), warnings = directory.resolve("scenario.warnings.yml");
        Files.writeString(output,"keep workbook"); Files.createDirectory(warnings); Files.writeString(warnings.resolve("child"),"keep child");
        assertThat(new RootCommand().execute(new StringWriter(),"convert","postman","--input","samples/postman/sample-collection.json","--output",output.toString())).isEqualTo(2);
        assertThat(Files.readString(output)).isEqualTo("keep workbook"); assertThat(Files.readString(warnings.resolve("child"))).isEqualTo("keep child");
        try (var files = Files.list(directory)) { assertThat(files.noneMatch(file -> file.getFileName().toString().matches(".*_[0-9]{8}_[0-9]{6}_[0-9]{3}(?:_[0-9]+)?(\\..*)?"))).isTrue(); }
    }
}
