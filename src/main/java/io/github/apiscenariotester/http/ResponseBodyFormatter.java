package io.github.apiscenariotester.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import java.io.IOException;
import java.io.StringWriter;

/** Formats a single JSON value while preserving duplicate fields and number precision. */
public final class ResponseBodyFormatter {
    private static final JsonFactory JSON = new JsonFactory();
    private ResponseBodyFormatter() { }

    public static String pretty(String body) {
        var output = new StringWriter();
        try (var parser = JSON.createParser(body); var generator = JSON.createGenerator(output)) {
            var indent = new DefaultIndenter("  ", "\n");
            generator.setPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(indent).withArrayIndenter(indent));
            boolean seen = false;
            int depth = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (seen && depth == 0) return body; // Multiple root values are not a JSON document.
                seen = true;
                if (token.isStructStart()) depth++;
                else if (token.isStructEnd()) depth--;
                if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)
                    generator.writeNumber(parser.getText());
                else generator.copyCurrentEvent(parser);
            }
            if (!seen || depth != 0) return body;
            generator.flush();
            return output.toString();
        } catch (IOException exception) {
            return body;
        }
    }
}
