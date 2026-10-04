package io.github.apiscenariotester.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CurlRequest(String method, String url, Map<String, String> headers, String body,
        long connectTimeoutMs, long readTimeoutMs) {
    public CurlRequest {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }
}
