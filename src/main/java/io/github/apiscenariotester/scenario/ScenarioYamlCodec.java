package io.github.apiscenariotester.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ScenarioYamlCodec {

    private final ObjectMapper mapper = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                    .build());

    public ScenarioDocument read(Path input) throws IOException {
        return mapper.readValue(input.toFile(), ScenarioDocument.class);
    }

    public void write(ScenarioDocument document, Path output) throws IOException {
        try (OutputStream stream = Files.newOutputStream(
                output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            mapper.writeValue(stream, document);
        }
    }
}
