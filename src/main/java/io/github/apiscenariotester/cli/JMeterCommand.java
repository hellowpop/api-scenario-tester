package io.github.apiscenariotester.cli;

import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.jmeter.JMeterScenarioWriter;
import io.github.apiscenariotester.conversion.jmeter.JMeterTestPlanReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "jmeter", description = "Converts a JMeter JMX test plan to scenario Excel.")
public final class JMeterCommand implements Callable<Integer> {
    @Option(names = "--input", required = true) private Path input;
    @Option(names = "--output", required = true) private Path output;
    @Spec private CommandSpec commandSpec;

    @Override
    public Integer call() {
        try {
            ConversionResult result = new JMeterTestPlanReader().read(input);
            new JMeterScenarioWriter().write(result, output);
            commandSpec.commandLine().getOut().printf("Converted %d request(s): %s%n", result.scenarios().size(), output);
            if (!result.warnings().isEmpty()) {
                commandSpec.commandLine().getOut().println("Warnings: " + JMeterScenarioWriter.warningPath(output));
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            commandSpec.commandLine().getErr().println("Unable to convert JMeter test plan: " + exception.getMessage());
            return 2;
        }
    }
}
