package io.github.apiscenariotester.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Preserves existing result files in the same directory before writing new results. */
final class OutputFiles {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT);
    private OutputFiles() { }

    static void archiveExisting(PrintWriter console, Path... outputs) throws IOException {
        archiveExisting(console, Clock.systemDefaultZone(), outputs);
    }

    static void archiveExisting(PrintWriter console, Clock clock, Path... outputs) throws IOException {
        // Check every destination before moving any member of a conversion output set.
        for (Path output : outputs) {
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Output is not a regular file: " + output);
        }
        for (Path output : outputs) Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        String timestamp = LocalDateTime.now(clock).format(TIMESTAMP);
        for (Path output : outputs) {
            if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) continue;
            Path source = output.toAbsolutePath().normalize();
            long sequence = 0;
            while (true) {
                Path backup = source.resolveSibling(backupName(source.getFileName().toString(), timestamp, sequence));
                try {
                    Files.move(source, backup);
                    console.println("Renamed existing output: " + source + " -> " + backup);
                    break;
                } catch (FileAlreadyExistsException collision) {
                    // Keep a stable timestamp and add a counter; never replace an existing backup.
                    sequence++;
                }
            }
        }
    }

    private static String backupName(String fileName, String timestamp, long sequence) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        return base + "_" + timestamp + (sequence == 0 ? "" : "_" + sequence) + extension;
    }
}
