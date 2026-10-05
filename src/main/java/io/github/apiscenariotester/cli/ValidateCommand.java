package io.github.apiscenariotester.cli;

import io.github.apiscenariotester.execution.ScenarioRunPlanReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "validate", description = "Validates scenario input without HTTP calls.", mixinStandardHelpOptions = true)
public final class ValidateCommand implements Callable<Integer> {
    @Option(names = "--scenario", required = true) private Path scenario;
    @Option(names = "--config") private Path config;
    @Spec private CommandSpec spec;

    @Override public Integer call() {
        try {
            var plan = new ScenarioRunPlanReader().read(scenario, config);
            spec.commandLine().getOut().printf("Valid scenario: %d sequential step(s), %d iteration(s) per session, %d session(s)%n", plan.steps().size(), plan.iterations(), plan.sessions());
            return 0;
        } catch (IOException | RuntimeException exception) {
            spec.commandLine().getErr().println("Invalid scenario: " + exception.getMessage());
            return 2;
        }
    }
}
