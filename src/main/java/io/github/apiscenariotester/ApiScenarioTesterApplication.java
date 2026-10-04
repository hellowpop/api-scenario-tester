package io.github.apiscenariotester;

import io.github.apiscenariotester.cli.RootCommand;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public final class ApiScenarioTesterApplication {

    private ApiScenarioTesterApplication() {}

    public static void main(String[] args) {
        int exitCode = new RootCommand().execute(args);
        System.exit(exitCode);
    }
}
