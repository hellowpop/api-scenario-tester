package io.github.apiscenariotester.report;

import java.util.Map;

public record TimingStatistics(
        long minMs,
        long maxMs,
        double averageMs,
        double trimmedAverageMs,
        Map<Integer, Long> percentiles) {

    public TimingStatistics {
        percentiles = Map.copyOf(percentiles);
    }
}
