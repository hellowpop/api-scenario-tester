package io.github.apiscenariotester.cli;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "api-scenario-tester",
        description = "Executes sequential YAML API scenarios using curl.",
        mixinStandardHelpOptions = true,
        subcommands = {
            TemplateCommand.class,
            ValidateCommand.class,
            RunCommand.class,
            ConvertCommand.class
        })
public final class RootCommand implements Callable<Integer> {

    public int execute(String... args) {
        return new CommandLine(this).execute(args);
    }

    public int execute(Writer output, String... args) {
        CommandLine commandLine = new CommandLine(this);
        PrintWriter writer = new PrintWriter(output, true);
        commandLine.setOut(writer);
        commandLine.setErr(writer);
        return commandLine.execute(args);
    }

    @Override
    public Integer call() {
        return 0;
    }

}
