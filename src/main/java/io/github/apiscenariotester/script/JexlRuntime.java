package io.github.apiscenariotester.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.apiscenariotester.http.CurlRequest;
import io.github.apiscenariotester.http.CurlResponse;
import java.net.URI;
import java.util.*;
import org.apache.commons.jexl3.*;
import org.apache.commons.jexl3.introspection.JexlPermissions;

/** Compiled scripts are shared; mutable contexts belong exclusively to each run. */
public final class JexlRuntime {
    private final JexlEngine engine = new JexlBuilder().strict(true).silent(false).safe(false)
        .permissions(JexlPermissions.SECURE.compose("io.github.apiscenariotester.script { +ExecutionControl { stop(); isStopped(); getReason(); } }"))
        .features(new JexlFeatures().sideEffectGlobal(true).newInstance(false).loops(false)
            .lambda(false).pragma(false).annotation(false)).cache(256).create();
    private final Map<String, Entry> scripts = new LinkedHashMap<>();
    private final Map<String, List<Part>> templates = new java.util.concurrent.ConcurrentHashMap<>();
    private final JexlEngine expressions = new JexlBuilder().strict(true).silent(false).safe(false)
        .permissions(JexlPermissions.SECURE).features(new JexlFeatures().sideEffect(false).sideEffectGlobal(false)
            .newInstance(false).loops(false).lambda(false).pragma(false).annotation(false)).cache(256).create();
    private record Entry(String phase, JexlScript script) { }
    private record Part(String literal, String source, JexlExpression expression) { }

    public JexlRuntime(List<Map<String,String>> rows) {
        for (var row : rows) {
            String id = required(row, "id"), phase = required(row, "phase").toUpperCase(Locale.ROOT);
            if (!Set.of("PRE","FILTER","VALIDATE","POST").contains(phase)) throw new IllegalArgumentException("invalid script phase: " + phase);
            if (scripts.containsKey(id)) throw new IllegalArgumentException("duplicate script id: " + id);
            try { scripts.put(id, new Entry(phase, engine.createScript(new JexlInfo("script:" + id, 1, 1), required(row, "body")))); }
            catch (RuntimeException e) { throw new IllegalArgumentException("script '" + id + "': " + e.getMessage(), e); }
        }
    }
    public void validateReferences(String phase, List<String> ids) {
        for (String id : ids) {
            var entry = scripts.get(id);
            if (entry == null) throw new IllegalArgumentException("unknown script: " + id);
            if (!entry.phase.equals(phase)) throw new IllegalArgumentException("script '" + id + "' requires phase " + entry.phase + ", used in " + phase);
        }
    }
    public void execute(String phase, List<String> ids, MapContext context) {
        for (String id : ids) {
            try {
                Object result = scripts.get(id).script.execute(context);
                if (phase.equals("VALIDATE") && !Boolean.TRUE.equals(result))
                    throw new IllegalArgumentException("validation must return true (received " + result + ")");
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("script '" + id + "' [" + phase + "]: " + e.getMessage() + responseDiagnostic(context), e);
            }
        }
    }
    private static String responseDiagnostic(MapContext context) {
        if (!(context.get("response") instanceof Map<?,?> response)) return "";
        Object names = response.get("headers") instanceof Map<?,?> headers ? headers.keySet() : List.of();
        return "; response status=" + response.get("status") + "; response header names=" + names;
    }
    public String validateTemplate(String text) {
        var result = new StringBuilder();
        for (var part : parts(text)) result.append(part.expression == null ? part.literal : "placeholder");
        return result.toString();
    }
    public String render(String text, MapContext context) {
        var result = new StringBuilder();
        for (var part : parts(text)) {
            if (part.expression == null) { result.append(part.literal); continue; }
            Object value = part.expression.evaluate(context);
            if (value == null) throw new IllegalArgumentException("undefined template value: " + part.source);
            if (value instanceof Map || value instanceof Collection) throw new IllegalArgumentException("template value must be scalar: " + part.source);
            result.append(value);
        }
        return result.toString();
    }
    private List<Part> parts(String text) {
        return templates.computeIfAbsent(text, input -> {
            var result = new ArrayList<Part>(); int offset = 0;
            while (offset < input.length()) {
                int start = input.indexOf("${", offset);
                if (start < 0) { result.add(new Part(input.substring(offset), null, null)); break; }
                result.add(new Part(input.substring(offset, start), null, null));
                int depth = 1, end = start + 2; char quote = 0; boolean escape = false;
                for (; end < input.length(); end++) {
                    char c = input.charAt(end);
                    if (quote != 0) {
                        if (escape) escape = false;
                        else if (c == '\\') escape = true;
                        else if (c == quote) quote = 0;
                    } else if (c == '\'' || c == '"' || c == '`') quote = c;
                    else if (c == '{') depth++;
                    else if (c == '}' && --depth == 0) break;
                }
                if (depth != 0) throw new IllegalArgumentException("unclosed template expression: " + input.substring(start));
                String source = input.substring(start + 2, end);
                if (source.isBlank()) throw new IllegalArgumentException("empty template expression");
                result.add(new Part(null, source, expressions.createExpression(source))); offset = end + 1;
            }
            return List.copyOf(result);
        });
    }
    public Map<String,Object> request(CurlRequest request) {
        var result = new LinkedHashMap<String,Object>();
        result.put("method", request.method()); result.put("path", request.url());
        result.put("headers", new LinkedHashMap<>(request.headers())); result.put("body", request.body());
        return result;
    }
    public CurlRequest renderRequest(CurlRequest original, Map<String,Object> request, MapContext context) {
        String url = render(Objects.toString(request.get("path"), ""), context);
        if (!url.matches("(?i)^https?://.*")) {
            URI originalUri = URI.create(render(originTemplate(original.url()), context));
            url = originalUri.getScheme() + "://" + originalUri.getRawAuthority() + "/" + url.replaceAll("^/+", "");
        }
        URI uri = URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
            throw new IllegalArgumentException("invalid HTTP URL: " + url);
        String method = Objects.toString(request.get("method"), "").toUpperCase(Locale.ROOT);
        if (!Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(method)) throw new IllegalArgumentException("invalid method: " + method);
        if (!(request.get("headers") instanceof Map<?,?> source)) throw new IllegalArgumentException("request.headers must be a map");
        var headers = new LinkedHashMap<String,String>();
        source.forEach((key,value) -> {
            if (!(key instanceof String name) || !(value instanceof String text)) throw new IllegalArgumentException("headers must contain strings");
            String rendered = render(text, context); validateHeader(name, rendered); headers.put(name, rendered);
        });
        String body = render(Objects.toString(request.get("body"), ""), context);
        if (method.equals("HEAD") && !body.isEmpty()) throw new IllegalArgumentException("HEAD request body is not supported");
        return new CurlRequest(method, url, headers, body, original.connectTimeoutMs(), original.readTimeoutMs());
    }
    private String originTemplate(String url) {
        int start = url.indexOf("://") + 3;
        var origin = new StringBuilder(url.substring(0, start));
        for (var part : parts(url.substring(start))) {
            if (part.expression != null) { origin.append("${").append(part.source).append('}'); continue; }
            for (int index = 0; index < part.literal.length(); index++) {
                char c = part.literal.charAt(index);
                if (c == '/' || c == '?' || c == '#') return origin.toString();
                origin.append(c);
            }
        }
        return origin.toString();
    }
    public static void validateHeader(String name, String value) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127))
            throw new IllegalArgumentException("invalid header: " + name);
    }
    public Map<String,Object> response(CurlResponse response) {
        var headers = new LinkedHashMap<String,List<String>>();
        for (String line : response.headers().split("\\r?\\n")) {
            if (line.startsWith("HTTP/")) headers.clear();
            int colon = line.indexOf(':');
            if (colon > 0) headers.computeIfAbsent(line.substring(0,colon).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(line.substring(colon+1).trim());
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("status", response.status()); result.put("headers", freeze(headers)); result.put("body", response.body());
        result.put("elapsedMs", response.elapsedMs()); result.put("exitCode", response.exitCode());
        Object json = null;
        try { json = new ObjectMapper().readValue(response.body(), Object.class); } catch (java.io.IOException ignored) { }
        result.put("json", freeze(json)); return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?,?> map) {
            var copy = new LinkedHashMap<Object,Object>(); map.forEach((k,v) -> copy.put(k,freeze(v))); return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(JexlRuntime::freeze).toList());
        return value;
    }
    private static String required(Map<String,String> row, String key) {
        String value = row.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("script missing " + key); return value;
    }
}
