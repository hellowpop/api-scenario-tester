package io.github.apiscenariotester.cli;

import io.github.apiscenariotester.input.excel.ScenarioTemplateWriter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "template", description = "Creates an Excel scenario template.")
public final class TemplateCommand implements Callable<Integer> {

    @Option(names = "--output", required = true, description = "Destination .xlsx file")
    private Path output;

    @Spec
    private CommandSpec commandSpec;

    private final ScenarioTemplateWriter templateWriter = new ScenarioTemplateWriter();

    @Override
    public Integer call() {
        try {
            templateWriter.write(output);
            commandSpec.commandLine().getOut().println("Created template: " + output);
            return 0;
        } catch (FileAlreadyExistsException exception) {
            commandSpec.commandLine().getErr().println("Output already exists: " + output);
            return 2;
        } catch (IOException exception) {
            commandSpec.commandLine().getErr().println("Unable to create template: " + exception.getMessage());
            return 2;
        }
    }
}
