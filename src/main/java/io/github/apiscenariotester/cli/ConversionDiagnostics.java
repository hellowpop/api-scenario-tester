package io.github.apiscenariotester.cli;

import java.nio.file.Path;

/** Includes operation and resolved paths when filesystem exceptions only provide a filename. */
final class ConversionDiagnostics {
    private ConversionDiagnostics() { }
    static String describe(String stage, Path input, Path output, Exception exception) {
        return "stage=" + stage + "; input=" + input.toAbsolutePath().normalize()
                + "; output=" + output.toAbsolutePath().normalize() + "; "
                + exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }
}
