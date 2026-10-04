package io.github.apiscenariotester.cli;

import io.github.apiscenariotester.scenario.ScenarioExcelCodec;
import io.github.apiscenariotester.scenario.ScenarioYamlCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "yaml", description = "Converts execution YAML to scenario Excel.")
public final class YamlToExcelCommand implements Callable<Integer> {
    @Option(names = "--input", required = true) private Path input;
    @Option(names = "--output", required = true) private Path output;
    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        String stage = "read input";
        try {
            var document = new ScenarioYamlCodec().read(input);
            stage = "prepare output";
            OutputFiles.archiveExisting(spec.commandLine().getOut(), output);
            stage = "write output";
            new ScenarioExcelCodec().write(document, output);
            spec.commandLine().getOut().println("Converted scenario: " + output);
            return 0;
        } catch (IOException | RuntimeException exception) {
            spec.commandLine().getErr().println("Unable to convert YAML scenario: " + ConversionDiagnostics.describe(stage, input, output, exception));
            return 2;
        }
    }
}
