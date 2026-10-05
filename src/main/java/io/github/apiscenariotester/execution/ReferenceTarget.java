package io.github.apiscenariotester.execution;

import io.github.apiscenariotester.http.CurlRequest;
import io.github.apiscenariotester.script.JexlRuntime;
import java.net.URI;
import org.apache.commons.jexl3.MapContext;

/** Maps the rendered base request onto a separately configured reference server. */
public record ReferenceTarget(String baseUrl, String referenceUrl, ComparisonRules rules) {
    public static ReferenceTarget validated(String baseUrl, String referenceUrl, JexlRuntime scripts) {
        return validated(baseUrl, referenceUrl, scripts, ComparisonRules.none());
    }
    public static ReferenceTarget validated(String baseUrl, String referenceUrl, JexlRuntime scripts, ComparisonRules rules) {
        base(scripts.validateTemplate(baseUrl), "baseUrl");
        base(scripts.validateTemplate(referenceUrl), "referenceUrl");
        return new ReferenceTarget(baseUrl, referenceUrl, rules);
    }

    public CurlRequest request(CurlRequest request, JexlRuntime scripts, MapContext context) {
        URI base = base(scripts.render(baseUrl, context), "baseUrl");
        URI reference = base(scripts.render(referenceUrl, context), "referenceUrl");
        URI actual = URI.create(request.url());
        String path = actual.getRawPath();
        String prefix = base.getRawPath().replaceAll("/+$", "");
        if (java.util.Objects.equals(base.getScheme(), actual.getScheme())
                && java.util.Objects.equals(base.getRawAuthority(), actual.getRawAuthority())
                && (path.equals(prefix) || path.startsWith(prefix + "/"))) path = path.substring(prefix.length());
        String url = path.isEmpty() ? reference.toString()
                : reference.toString().replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
        if (actual.getRawQuery() != null) url += "?" + actual.getRawQuery();
        return new CurlRequest(request.method(), url, request.headers(), request.body(),
                request.connectTimeoutMs(), request.readTimeoutMs());
    }

    private static URI base(String value, String label) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException(label + " must be an absolute HTTP URL without query or fragment: " + value);
        return uri;
    }
}
