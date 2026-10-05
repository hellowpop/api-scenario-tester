package io.github.apiscenariotester.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.apiscenariotester.http.CurlRequest;
import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioYamlCodec;
import io.github.apiscenariotester.script.JexlRuntime;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles all input before any HTTP side effect. */
public final class ScenarioRunPlanReader {
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^{}]+)}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final List<String> SCRIPT_COLUMNS = List.of("preScripts", "filterScripts", "validationScripts", "postScripts");
    private final ObjectMapper json = new ObjectMapper();
    private JexlRuntime scripts;

    public ScenarioRunPlan read(Path scenario, Path config) throws IOException {
        ScenarioDocument document = new ScenarioYamlCodec().read(scenario);
        Path scenarioDirectory = scenario.toAbsolutePath().normalize().getParent();
        scripts = new JexlRuntime(document.scripts());
        Map<String, Object> runtime = config == null ? Map.of() : new ObjectMapper(new YAMLFactory())
                .readValue(config.toFile(), new TypeReference<Map<String, Object>>() { });
        if (runtime == null) throw new IllegalArgumentException("runtime config must be a mapping");
        Map<String, String> globals = new LinkedHashMap<>();
        objectMap(runtime.get("globals"), "globals").forEach((key, value) -> globals.put(key, environment(scalar(value, "global." + key))));
        Map<String, String> common = new LinkedHashMap<>(document.common());
        objectMap(runtime.get("hosts"), "hosts").forEach((name, value) ->
                objectMap(value, "host " + name).forEach((key, setting) ->
                        common.put("host." + name + "." + key, environment(scalar(setting, "host." + name + "." + key)))));
        int sessions = positiveInt(common.getOrDefault("sessions", "1"), "sessions");
        int iterations = positiveInt(common.getOrDefault("iterations", "1"), "iterations");
        boolean continueOnFailure = bool(common.getOrDefault("continueOnFailure", "false"), "continueOnFailure");
        long min = nonNegative(common.getOrDefault("waitMinMs", "0"), "waitMinMs");
        long max = nonNegative(common.getOrDefault("waitMaxMs", Long.toString(min)), "waitMaxMs");
        WaitPolicy wait = new WaitPolicy(WaitPattern.valueOf(common.getOrDefault("waitPattern", "FIXED")), min, max);
        String executable = runtime.containsKey("curlExecutable") ? environment(scalar(runtime.get("curlExecutable"), "curlExecutable"))
                : System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "curl.exe" : "curl";
        if (executable.isBlank()) throw new IllegalArgumentException("curlExecutable must not be blank");

        Map<String, List<ScenarioRunPlan.Step>> subsets = new LinkedHashMap<>();
        for (Map<String, String> row : document.subScenarios()) {
            String id = required(row, "subsetId");
            if (!row.getOrDefault("preSubsets", "").isBlank()) throw new IllegalArgumentException("nested subsets are not supported: " + id);
            if (row.getOrDefault("method", "").equalsIgnoreCase("SUBSET")) throw new IllegalArgumentException("nested SUBSET calls are not supported: " + id);
            subsets.computeIfAbsent(id, ignored -> new ArrayList<>()).add(step(row, common, scenarioDirectory));
        }
        subsets.forEach((id, rows) -> sortAndCheck(rows, "subset " + id));
        List<Map<String, String>> main = new ArrayList<>(document.mainScenarios());
        main.sort(Comparator.comparingInt(row -> positiveInt(required(row, "order"), "order")));
        Set<Integer> mainOrders = new HashSet<>();
        List<ScenarioRunPlan.Step> steps = new ArrayList<>();
        for (Map<String, String> row : main) {
            if (row.getOrDefault("method", "").equalsIgnoreCase("SUBSET")) {
                int order = positiveInt(required(row, "order"), "order");
                required(row, "name");
                if (!mainOrders.add(order)) throw new IllegalArgumentException("duplicate main order: " + order);
                String id = required(row, "path");
                if (!subsets.containsKey(id)) throw new IllegalArgumentException("unknown subset: " + id);
                for (String column : SCRIPT_COLUMNS) if (!row.getOrDefault(column, "").isBlank())
                    throw new IllegalArgumentException("scripts belong on subset HTTP rows: " + column);
                if (!row.getOrDefault("preSubsets", "").isBlank()) throw new IllegalArgumentException("SUBSET row cannot have preSubsets");
                if (bool(row.getOrDefault("enabled", "true"), "enabled")) steps.addAll(subsets.get(id));
                continue;
            }
            ScenarioRunPlan.Step step = step(row, common, scenarioDirectory);
            if (!mainOrders.add(step.order())) throw new IllegalArgumentException("duplicate main order: " + step.order());
            List<ScenarioRunPlan.Step> before = new ArrayList<>();
            for (String id : csv(row.getOrDefault("preSubsets", ""))) {
                if (!subsets.containsKey(id)) throw new IllegalArgumentException("unknown subset: " + id);
                before.addAll(subsets.get(id));
            }
            if (bool(row.getOrDefault("enabled", "true"), "enabled")) { steps.addAll(before); steps.add(step); }
        }
        Map<String, String> format = document.resultFormat();
        double trim = Double.parseDouble(format.getOrDefault("trimPercent", "5"));
        List<Integer> percentiles = csv(format.getOrDefault("percentiles", "50,90,95,99")).stream().map(Integer::valueOf).toList();
        // Reuse the statistics contract to validate settings even when no calls will run.
        new io.github.apiscenariotester.report.StatisticsCalculator().calculate(List.of(0L), trim, percentiles);
        String output = format.getOrDefault("output", "results.xlsx");
        if (output.toLowerCase(Locale.ROOT).matches(".*\\.ya?ml$")) output = output.replaceFirst("(?i)\\.ya?ml$", ".xlsx");
        return new ScenarioRunPlan(steps, sessions, iterations, continueOnFailure, wait, executable, Path.of(output), trim, percentiles, globals, scripts);
    }

    private ScenarioRunPlan.Step step(Map<String, String> row, Map<String, String> common, Path scenarioDirectory) throws IOException {
        String name = required(row, "name");
        try {
            Map<String, List<String>> scriptIds = new LinkedHashMap<>();
            for (String column : SCRIPT_COLUMNS) {
                String phase = switch (column) { case "preScripts" -> "PRE"; case "filterScripts" -> "FILTER"; case "validationScripts" -> "VALIDATE"; default -> "POST"; };
                List<String> ids = csv(row.getOrDefault(column, ""));
                scripts.validateReferences(phase, ids); scriptIds.put(phase, ids);
            }
            int order = positiveInt(required(row, "order"), "order");
            String method = required(row, "method").toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) throw new IllegalArgumentException("unsupported method: " + method);
            String path = required(row, "path");
            String host = row.getOrDefault("host", "");
            String base = common.getOrDefault("host." + host + ".baseUrl", "");
            String referenceUrl = common.getOrDefault("host." + host + ".referenceUrl", "");
            ReferenceTarget reference = referenceUrl.isBlank() ? null : ReferenceTarget.validated(base, referenceUrl, scripts,
                    ComparisonRules.compile(common.getOrDefault("host." + host + ".compareSkipHeader", common.getOrDefault("compareSkipHeader", "")),
                            common.getOrDefault("host." + host + ".compareSkipBody", common.getOrDefault("compareSkipBody", ""))));
            if (!path.matches("(?i)^https?://.*") && base.isBlank()) throw new IllegalArgumentException("unknown host: " + host);
            String url = path.matches("(?i)^https?://.*") ? path : base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
            URI uri = URI.create(scripts.validateTemplate(url));
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException("invalid HTTP URL: " + url);
            Map<String, String> headers = new LinkedHashMap<>();
            String headerText = referencedText(row.getOrDefault("headers", "{}"), scenarioDirectory, "headers");
            if (!headerText.isBlank()) {
                var headerNode = json.readTree(headerText);
                if (headerNode == null || !headerNode.isObject()) throw new IllegalArgumentException("headers must be a JSON object");
                var fields = headerNode.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (!field.getValue().isTextual()) throw new IllegalArgumentException("header values must be strings");
                    String key = field.getKey();
                    String value = referencedText(field.getValue().asText(), scenarioDirectory, "header " + key);
                    scripts.validateTemplate(value);
                    if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                            || value.chars().anyMatch(character -> (character < 32 && character != '\t') || character == 127))
                        throw new IllegalArgumentException("invalid header: " + key);
                    headers.put(key, value);
                }
            }
            String body = referencedText(row.getOrDefault("body", ""), scenarioDirectory, "body");
            scripts.validateTemplate(body);
            if (method.equals("HEAD") && !body.isEmpty()) throw new IllegalArgumentException("HEAD request body is not supported");
            long connect = positiveInt(common.getOrDefault("host." + host + ".connectTimeoutMs", "3000"), "connectTimeoutMs");
            long read = positiveInt(common.getOrDefault("host." + host + ".readTimeoutMs", "10000"), "readTimeoutMs");
            String status = row.getOrDefault("expectedStatus", "200-299");
            if (!status.matches("[1-5][0-9]{2}(-[1-5][0-9]{2})?")) throw new IllegalArgumentException("invalid expectedStatus: " + status);
            String[] range = status.split("-");
            int statusMin = Integer.parseInt(range[0]);
            int statusMax = Integer.parseInt(range[range.length - 1]);
            if (statusMin > statusMax) throw new IllegalArgumentException("invalid status range: " + status);
            String expectedMax = row.getOrDefault("expectedMaxMs", "");
            return new ScenarioRunPlan.Step(order, name, new CurlRequest(method, url, headers, body, connect, read),
                    statusMin, statusMax, expectedMax.isBlank() ? null : nonNegative(expectedMax, "expectedMaxMs"), scriptIds, reference);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("scenario '" + name + "': " + exception.getMessage(), exception);
        }
    }

    private static String referencedText(String value, Path scenarioDirectory, String label) throws IOException {
        if (!value.startsWith("ref:")) return value;
        String filename = value.substring(4).strip();
        if (filename.isEmpty()) throw new IllegalArgumentException(label + " reference path must not be blank");
        Path file = scenarioDirectory.resolve(filename).normalize();
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IOException("Unable to read " + label + " reference file: " + file + " ("
                    + exception.getClass().getSimpleName() + ")", exception);
        }
    }

    private static void sortAndCheck(List<ScenarioRunPlan.Step> steps, String section) {
        steps.sort(Comparator.comparingInt(ScenarioRunPlan.Step::order));
        Set<Integer> orders = new HashSet<>();
        for (var step : steps) if (!orders.add(step.order())) throw new IllegalArgumentException("duplicate order in " + section);
    }

    private static Map<String, Object> objectMap(Object value, String label) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException(label + " must be a mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, entry) -> result.put(key.toString(), entry));
        return result;
    }

    private static String scalar(Object value, String label) {
        if (value == null || value instanceof Map || value instanceof List) throw new IllegalArgumentException(label + " must be a scalar");
        return value.toString();
    }

    private static String environment(String value) {
        return substitute(value, expression -> {
            String result = System.getenv(expression);
            if (result == null) throw new IllegalArgumentException("undefined environment variable: " + expression);
            return result;
        });
    }

    private static String substitute(String value, java.util.function.Function<String, String> lookup) {
        Matcher matcher = TEMPLATE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(lookup.apply(matcher.group(1))));
        matcher.appendTail(result);
        return result.toString();
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static int positiveInt(String value, String label) {
        int number = Integer.parseInt(value);
        if (number < 1) throw new IllegalArgumentException(label + " must be positive");
        return number;
    }

    private static long nonNegative(String value, String label) {
        long number = Long.parseLong(value);
        if (number < 0) throw new IllegalArgumentException(label + " must not be negative");
        return number;
    }

    private static boolean bool(String value, String label) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(label + " must be true or false");
        return Boolean.parseBoolean(value);
    }

    private static List<String> csv(String value) {
        return value.isBlank() ? List.of() : java.util.Arrays.stream(value.split(",")).map(String::trim).toList();
    }
}
