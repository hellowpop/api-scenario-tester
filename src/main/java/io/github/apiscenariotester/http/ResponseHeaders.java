package io.github.apiscenariotester.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Final HTTP header block, with case-insensitive names and ordered repeated values. */
public final class ResponseHeaders {
    private ResponseHeaders() { }

    public static Map<String, List<String>> parse(String text) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith("HTTP/")) { headers.clear(); continue; }
            int colon = line.indexOf(':');
            if (colon > 0) headers.computeIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    ignored -> new ArrayList<>()).add(line.substring(colon + 1).trim());
        }
        headers.replaceAll((key, values) -> List.copyOf(values));
        return java.util.Collections.unmodifiableMap(headers);
    }
}
