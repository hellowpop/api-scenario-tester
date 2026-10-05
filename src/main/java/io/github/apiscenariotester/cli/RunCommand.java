package io.github.apiscenariotester.cli;

import io.github.apiscenariotester.execution.ScenarioRunPlanReader;
import io.github.apiscenariotester.execution.ScenarioRunner;
import io.github.apiscenariotester.report.ExecutionExcelWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "run", description = "Runs parallel sessions of sequential curl API calls and writes an Excel report.", mixinStandardHelpOptions = true)
public final class RunCommand implements Callable<Integer> {
    @Option(names = "--scenario", required = true, description = "Version 1 scenario YAML") private Path scenario;
    @Option(names = "--config", description = "Optional runtime YAML") private Path config;
    @Option(names = "--output", description = "Result Excel (.xlsx)") private Path output;
    @Option(names = "--debug", description = "Records each curl input/output in curl/<uuid>.txt with Excel links") private boolean debug;
    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        Path reserved = null;
        Path temporary = null;
        try {
            var plan = new ScenarioRunPlanReader().read(scenario, config);
            Path result = (output == null ? plan.output() : output).toAbsolutePath().normalize();
            if (!result.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                throw new IllegalArgumentException("output must end with .xlsx");
            Files.createDirectories(result.getParent());
            OutputFiles.archiveExisting(spec.commandLine().getOut(), result);
            Files.createFile(result); // Reserve atomically before making any API call.
            reserved = result;
            var calls = new ScenarioRunner().run(plan, result, debug);
            temporary = result.resolveSibling("." + result.getFileName() + "." + UUID.randomUUID() + ".tmp");
            new ExecutionExcelWriter().write(calls, plan, temporary);
            try { Files.move(temporary, result, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING); }
            reserved = null;
            long failed = calls.stream().filter(call -> !call.success()).count();
            spec.commandLine().getOut().printf("Executed %d call(s), failed %d. Result: %s%n", calls.size(), failed, result);
            return failed == 0 ? 0 : 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            spec.commandLine().getErr().println("Scenario execution interrupted");
            return 1;
        } catch (IOException | RuntimeException exception) {
            spec.commandLine().getErr().println("Unable to run scenario: " + exception.getMessage());
            return 2;
        } finally {
            try {
                if (temporary != null) Files.deleteIfExists(temporary);
                if (reserved != null) Files.deleteIfExists(reserved);
            } catch (IOException exception) { spec.commandLine().getErr().println("Unable to remove incomplete result: " + exception.getMessage()); }
        }
    }
}
