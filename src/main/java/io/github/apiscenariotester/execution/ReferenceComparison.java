package io.github.apiscenariotester.execution;

import io.github.apiscenariotester.http.CurlResponse;
import io.github.apiscenariotester.http.ResponseHeaders;
import io.github.apiscenariotester.http.ResponseBodyFormatter;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

public record ReferenceComparison(String url, CurlResponse response, boolean requested, boolean statusMatch,
        boolean headersMatch, boolean responseMatch, String detail) {
    private static final Pattern HTTP_SCHEME = Pattern.compile("\\bhttps?://", Pattern.CASE_INSENSITIVE);
    public boolean matches() { return statusMatch && headersMatch && responseMatch; }

    public static ReferenceComparison failure(String url, String error) {
        return new ReferenceComparison(url, new CurlResponse(0, -1, 0, "", "", error, null),
                false, false, false, false, "reference request: " + error);
    }

    public static ReferenceComparison compare(String url, CurlResponse base, CurlResponse reference) {
        return compare(url, base, reference, ComparisonRules.none());
    }

    public static ReferenceComparison compare(String url, CurlResponse base, CurlResponse reference, ComparisonRules rules) {
        var details = new ArrayList<String>();
        boolean valid = true;
        if (base.exitCode() != 0 || !base.error().isEmpty()) {
            details.add("base curl: " + base.error()); valid = false;
        }
        if (reference.exitCode() != 0 || !reference.error().isEmpty()) {
            details.add("reference curl: " + reference.error()); valid = false;
        }
        boolean status = valid && base.status() == reference.status();
        if (valid && !status) details.add("status differs: base=" + base.status() + ", reference=" + reference.status());
        var baseHeaders = comparisonHeaders(base.headers(), rules);
        var referenceHeaders = comparisonHeaders(reference.headers(), rules);
        boolean headers = valid && baseHeaders.equals(referenceHeaders);
        if (valid && !headers) {
            var different = new TreeSet<>(baseHeaders.keySet()); different.addAll(referenceHeaders.keySet());
            different.removeIf(key -> java.util.Objects.equals(baseHeaders.get(key), referenceHeaders.get(key)));
            details.add("headers differ: " + different);
        }
        boolean body = false;
        try {
            String baseBody = comparisonText(ResponseBodyFormatter.pretty(rules.filterBody(base.body())));
            String referenceBody = comparisonText(ResponseBodyFormatter.pretty(rules.filterBody(reference.body())));
            body = valid && baseBody.equals(referenceBody);
            if (valid && !body) {
                int index = 0, limit = Math.min(baseBody.length(), referenceBody.length());
                while (index < limit && baseBody.charAt(index) == referenceBody.charAt(index)) index++;
                details.add("response differs at normalized character " + index + ": base length=" + baseBody.length()
                        + ", reference length=" + referenceBody.length());
            }
        } catch (RuntimeException exception) {
            details.add("body comparison: " + exception.getMessage());
        }
        return new ReferenceComparison(url, reference, true, status, headers, body, details.isEmpty() ? "matched" : String.join("; ", details));
    }

    private static String comparisonText(String text) {
        return HTTP_SCHEME.matcher(text).replaceAll("http://");
    }

    private static Map<String, List<String>> comparisonHeaders(String text, ComparisonRules rules) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        ResponseHeaders.parse(text).forEach((name, values) -> {
            if (!rules.skipsHeader(name)) result.put(name, values.stream().map(ReferenceComparison::comparisonText).toList());
        });
        return result;
    }
}
