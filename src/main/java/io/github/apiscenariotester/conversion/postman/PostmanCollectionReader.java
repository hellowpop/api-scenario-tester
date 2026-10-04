package io.github.apiscenariotester.conversion.postman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.ConvertedScenario;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PostmanCollectionReader {

    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://[^/]+)(/.*)?$");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^{}]+)}}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversionResult read(Path input) throws IOException {
        JsonNode root = objectMapper.readTree(input.toFile());
        List<ConvertedScenario> scenarios = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        addAuthenticationWarning(root.path("auth"), "Collection", warnings);
        visitItems(root.path("item"), scenarios, warnings);
        return new ConversionResult(scenarios, warnings);
    }

    private void visitItems(
            JsonNode items, List<ConvertedScenario> scenarios, List<String> warnings)
            throws IOException {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            if (item.path("item").isArray()) {
                visitItems(item.path("item"), scenarios, warnings);
                continue;
            }
            JsonNode request = item.path("request");
            if (request.isMissingNode()) {
                continue;
            }
            String name = item.path("name").asText("request-" + (scenarios.size() + 1));
            addScriptWarnings(item.path("event"), name, warnings);
            addAuthenticationWarning(request.path("auth"), name, warnings);
            scenarios.add(convertRequest(scenarios.size() + 1, name, request));
        }
    }

    private ConvertedScenario convertRequest(int order, String name, JsonNode request)
            throws IOException {
        String rawUrl = request.path("url").isTextual()
                ? request.path("url").asText()
                : request.path("url").path("raw").asText();
        Matcher urlMatcher = URL_PATTERN.matcher(rawUrl);
        String baseUrl = "";
        String path = rawUrl;
        if (urlMatcher.matches()) {
            baseUrl = urlMatcher.group(1);
            path = urlMatcher.group(2) == null ? "/" : urlMatcher.group(2);
        }

        ObjectNode headers = objectMapper.createObjectNode();
        for (JsonNode header : request.path("header")) {
            if (!header.path("disabled").asBoolean(false)) {
                headers.put(header.path("key").asText(), convertVariables(header.path("value").asText()));
            }
        }
        String body = "";
        JsonNode bodyNode = request.path("body");
        if ("raw".equals(bodyNode.path("mode").asText())) {
            body = convertVariables(bodyNode.path("raw").asText());
        }
        return new ConvertedScenario(
                order,
                name,
                request.path("method").asText("GET").toUpperCase(Locale.ROOT),
                convertVariables(baseUrl),
                convertVariables(path),
                objectMapper.writeValueAsString(headers),
                body);
    }

    private static void addScriptWarnings(JsonNode events, String name, List<String> warnings) {
        for (JsonNode event : events) {
            String phase = event.path("listen").asText();
            if (!phase.isBlank()) {
                warnings.add(name + " " + phase + " script is not converted");
            }
        }
    }

    private static void addAuthenticationWarning(
            JsonNode auth, String owner, List<String> warnings) {
        String type = auth.path("type").asText();
        if (!type.isBlank() && !"noauth".equals(type)) {
            warnings.add(owner + " authentication helper is not converted: " + type);
        }
    }

    private static String convertVariables(String value) {
        Matcher matcher = VARIABLE_PATTERN.matcher(value);
        StringBuilder converted = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    converted, Matcher.quoteReplacement("${global." + matcher.group(1).trim() + "}"));
        }
        matcher.appendTail(converted);
        return converted.toString();
    }
}
