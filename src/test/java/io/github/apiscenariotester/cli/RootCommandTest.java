package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class RootCommandTest {

    @Test
    void helpListsPublicCommands() {
        StringWriter output = new StringWriter();

        int exitCode = new RootCommand().execute(output, "--help");

        assertThat(exitCode).isZero();
        assertThat(output.toString())
                .contains("template")
                .contains("validate")
                .contains("run")
                .contains("convert");
    }
}
