package io.github.apiscenariotester.conversion;

public record ConvertedScenario(
        int order,
        String name,
        String method,
        String baseUrl,
        String path,
        String headersJson,
        String body) {}
