package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputFilesTest {
    @TempDir Path directory;
    @Test void fixedTimestampCollisionsPreserveFilesAndUseCounters() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00.123Z"),ZoneId.of("Asia/Seoul"));
        Path output = directory.resolve("results.xlsx"), first = directory.resolve("results_20261005_090000_123.xlsx"), second = directory.resolve("results_20261005_090000_123_1.xlsx");
        Files.writeString(first,"first"); Files.writeString(second,"second"); Files.writeString(output,"third");
        var console = new PrintWriter(new StringWriter());
        OutputFiles.archiveExisting(console,clock,output);
        assertThat(Files.readString(first)).isEqualTo("first"); assertThat(Files.readString(second)).isEqualTo("second");
        assertThat(Files.readString(directory.resolve("results_20261005_090000_123_2.xlsx"))).isEqualTo("third");
        Files.writeString(output,"fourth"); OutputFiles.archiveExisting(console,clock,output);
        assertThat(Files.readString(directory.resolve("results_20261005_090000_123_3.xlsx"))).isEqualTo("fourth");
        assertThat(output).doesNotExist();
    }
    @Test void batchUsesOneTimestampAndPreservesLastExtensionOrNoExtension() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00.123Z"),ZoneId.of("Asia/Seoul"));
        Path workbook = directory.resolve("scenario.xlsx"), warnings = directory.resolve("scenario.warnings.yml"), plain = directory.resolve("plain");
        Files.writeString(workbook,"workbook"); Files.writeString(warnings,"warnings"); Files.writeString(plain,"plain");
        OutputFiles.archiveExisting(new PrintWriter(new StringWriter()),clock,workbook,warnings,plain);
        assertThat(Files.readString(directory.resolve("scenario_20261005_090000_123.xlsx"))).isEqualTo("workbook");
        assertThat(Files.readString(directory.resolve("scenario.warnings_20261005_090000_123.yml"))).isEqualTo("warnings");
        assertThat(Files.readString(directory.resolve("plain_20261005_090000_123"))).isEqualTo("plain");
    }
}
