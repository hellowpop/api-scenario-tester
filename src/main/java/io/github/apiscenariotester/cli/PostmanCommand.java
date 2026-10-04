package io.github.apiscenariotester.cli;

import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.postman.PostmanCollectionReader;
import io.github.apiscenariotester.conversion.postman.PostmanScenarioWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "postman", description = "Converts a Postman Collection to scenario Excel.")
public final class PostmanCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, description = "Postman Collection JSON")
    private Path input;

    @Option(names = "--output", required = true, description = "Destination scenario .xlsx")
    private Path output;

    @Spec
    private CommandSpec commandSpec;

    private final PostmanCollectionReader reader = new PostmanCollectionReader();
    private final PostmanScenarioWriter writer = new PostmanScenarioWriter();

    @Override
    public Integer call() {
        String stage = "read input";
        try {
            ConversionResult conversion = reader.read(input);
            stage = "prepare output";
            OutputFiles.archiveExisting(commandSpec.commandLine().getOut(), output, PostmanScenarioWriter.warningPath(output));
            stage = "write output";
            writer.write(conversion, output);
            commandSpec.commandLine().getOut().printf(
                    "Converted %d request(s): %s%n", conversion.scenarios().size(), output);
            if (!conversion.warnings().isEmpty()) {
                commandSpec.commandLine().getOut().println(
                        "Warnings: " + PostmanScenarioWriter.warningPath(output));
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            commandSpec.commandLine().getErr().println(
                    "Unable to convert Postman collection: " + ConversionDiagnostics.describe(stage, input, output, exception));
            return 2;
        }
    }
}
