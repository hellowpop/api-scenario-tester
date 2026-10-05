package io.github.apiscenariotester.execution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import java.io.IOException;
import java.io.StringWriter;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;

/** Immutable selectors shared by sessions; each body is parsed into a fresh comparison copy. */
public final class ComparisonRules {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    private static final Configuration SELECT = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(JSON)).options(Option.AS_PATH_LIST).build();
    private static final JsonFactory TOKENS = new JsonFactory();
    private static final ComparisonRules NONE = compile("", "");
    private final Set<String> headers;
    private final List<JsonPath> paths;

    private ComparisonRules(Set<String> headers, List<JsonPath> paths) {
        this.headers = Set.copyOf(headers); this.paths = List.copyOf(paths);
    }
    public static ComparisonRules none() { return NONE; }

    public static ComparisonRules compile(String headerText, String bodyText) {
        Set<String> headers = new LinkedHashSet<>();
        for (String name : lines(headerText)) {
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
                throw new IllegalArgumentException("invalid compareSkipHeader name: " + name);
            headers.add(name.toLowerCase(Locale.ROOT));
        }
        var paths = new java.util.ArrayList<JsonPath>();
        for (String expression : lines(bodyText)) {
            try {
                if (!expression.startsWith("$")) throw new IllegalArgumentException("JSONPath must start with $");
                validateDelimiters(expression);
                for (String selector : expandPropertyUnion(expression)) paths.add(JsonPath.compile(selector));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("invalid compareSkipBody JSONPath '" + expression + "': " + exception.getMessage(), exception);
            }
        }
        return new ComparisonRules(headers, paths);
    }

    public boolean skipsHeader(String name) { return headers.contains(name.toLowerCase(Locale.ROOT)); }

    public String filterBody(String body) {
        if (paths.isEmpty()) return body;
        JsonNode copy;
        try { copy = JSON.readTree(body); } catch (IOException exception) { return body; }
        if (copy == null || copy.isMissingNode()) return body;
        Set<List<Object>> targets = new LinkedHashSet<>();
        for (JsonPath path : paths) {
            if (path.getPath().equals("$")) return "";
            try {
                JsonNode selected = path.read(copy, SELECT);
                selected.forEach(node -> {
                    Set<List<Object>> resolved = new LinkedHashSet<>();
                    resolvePath(copy, node.asText().substring(1), List.of(), resolved);
                    if (resolved.size() != 1) throw new IllegalArgumentException("ambiguous or unsupported selected path: " + node.asText());
                    targets.addAll(resolved);
                });
            } catch (PathNotFoundException ignored) {
                // Optional or absent fields do not change comparison content.
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("compareSkipBody '" + path.getPath() + "': " + exception.getMessage(), exception);
            }
        }
        if (targets.isEmpty()) return body;
        // Copy original tokens: excluded array entries never shift the addresses of later entries.
        // Generated paths are matched directly, not reparsed (property names may contain quotes).
        var output = new StringWriter();
        var remaining = new LinkedHashSet<>(targets);
        try (var parser = TOKENS.createParser(body); var generator = TOKENS.createGenerator(output)) {
            parser.nextToken();
            copyValue(parser, generator, List.of(), targets, remaining);
            generator.flush();
        } catch (IOException exception) {
            throw new IllegalArgumentException("compareSkipBody cannot filter JSON", exception);
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException("compareSkipBody unsupported selected paths: " + remaining);
        return output.toString();
    }

    private static void resolvePath(JsonNode node, String suffix, List<Object> canonical, Set<List<Object>> targets) {
        if (suffix.isEmpty()) { targets.add(canonical); return; }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                String segment = "['" + name + "']";
                if (suffix.startsWith(segment)) resolvePath(node.get(name), suffix.substring(segment.length()), childPath(canonical, name), targets);
            });
        } else if (node.isArray()) {
            int end = suffix.indexOf(']');
            if (end < 0 || !suffix.startsWith("[")) return;
            int index = Integer.parseInt(suffix.substring(1, end));
            if (index < 0) index += node.size();
            if (index >= 0 && index < node.size()) resolvePath(node.get(index), suffix.substring(end + 1), childPath(canonical, index), targets);
        }
    }

    // Leaf property unions are returned by Jayway as a merged object/path; expand them to node selectors.
    private static List<String> expandPropertyUnion(String expression) {
        int start = -1;
        char quote = 0;
        boolean escaped = false;
        var commas = new java.util.ArrayList<Integer>();
        for (int index = 0; index < expression.length(); index++) {
            char c = expression.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) {
                if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '[') { start = index; commas.clear(); }
            else if (c == ',') commas.add(index);
        }
        if (start < 0 || commas.isEmpty() || !expression.endsWith("]")) return List.of(expression);
        var parts = new java.util.ArrayList<String>();
        int from = start + 1;
        for (int end : commas) { parts.add(expression.substring(from, end).strip()); from = end + 1; }
        parts.add(expression.substring(from, expression.length() - 1).strip());
        if (parts.stream().anyMatch(part -> part.length() < 2 || (part.charAt(0) != '\'' && part.charAt(0) != '"')
                || part.charAt(part.length() - 1) != part.charAt(0))) return List.of(expression);
        String prefix = expression.substring(0, start);
        return parts.stream().map(part -> prefix + "[" + part + "]").toList();
    }

    private static List<Object> childPath(List<Object> parent, Object segment) {
        var result = new java.util.ArrayList<>(parent);
        result.add(segment);
        return List.copyOf(result);
    }

    private static void copyValue(JsonParser parser, JsonGenerator generator, List<Object> path,
            Set<List<Object>> targets, Set<List<Object>> remaining) throws IOException {
        if (targets.contains(path)) {
            remaining.removeIf(target -> target.size() >= path.size() && target.subList(0, path.size()).equals(path));
            parser.skipChildren();
            return;
        }
        if (parser.currentToken() == JsonToken.START_OBJECT) {
            generator.writeStartObject();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                parser.nextToken();
                List<Object> child = childPath(path, name);
                if (!targets.contains(child)) generator.writeFieldName(name);
                copyValue(parser, generator, child, targets, remaining);
            }
            generator.writeEndObject();
        } else if (parser.currentToken() == JsonToken.START_ARRAY) {
            generator.writeStartArray();
            int index = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY)
                copyValue(parser, generator, childPath(path, index++), targets, remaining);
            generator.writeEndArray();
        } else if (parser.currentToken().isNumeric()) generator.writeNumber(parser.getText());
        else generator.copyCurrentEvent(parser);
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).distinct().toList();
    }

    private static void validateDelimiters(String expression) {
        var brackets = new java.util.ArrayDeque<Character>();
        char quote = 0;
        boolean escaped = false, regex = false;
        for (int index = 0; index < expression.length(); index++) {
            char c = expression.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && (quote != 0 || regex)) { escaped = true; continue; }
            if (regex) { if (c == '/') regex = false; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '/' && expression.substring(0, index).stripTrailing().endsWith("~")) { regex = true; continue; }
            if (c == '[' || c == '(') brackets.push(c);
            else if (c == ']' || c == ')') {
                if (brackets.isEmpty() || brackets.pop() != (c == ']' ? '[' : '('))
                    throw new IllegalArgumentException("unbalanced JSONPath delimiters");
            }
        }
        if (!brackets.isEmpty() || quote != 0 || regex) throw new IllegalArgumentException("unclosed JSONPath delimiter");
    }
}
