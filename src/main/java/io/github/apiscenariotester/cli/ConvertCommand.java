package io.github.apiscenariotester.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
        name = "convert",
        description = "Converts external scenario formats.",
        subcommands = {
            PostmanCommand.class,
            JMeterCommand.class,
            ExcelToYamlCommand.class,
            YamlToExcelCommand.class
        })
public final class ConvertCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        return 0;
    }
}
