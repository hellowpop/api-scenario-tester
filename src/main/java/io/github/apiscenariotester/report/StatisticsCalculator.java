package io.github.apiscenariotester.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StatisticsCalculator {

    public TimingStatistics calculate(
            List<Long> samples, double trimPercent, List<Integer> requestedPercentiles) {
        Objects.requireNonNull(samples, "samples must not be null");
        Objects.requireNonNull(requestedPercentiles, "percentiles must not be null");
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("at least one sample is required");
        }
        if (!Double.isFinite(trimPercent) || trimPercent < 0 || trimPercent >= 50) {
            throw new IllegalArgumentException("trim percent must be at least 0 and less than 50");
        }

        List<Long> sorted = new ArrayList<>(samples);
        if (sorted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("samples must not contain null values");
        }
        if (sorted.stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("samples must be non-negative");
        }
        sorted.sort(Long::compareTo);

        Map<Integer, Long> percentiles = new LinkedHashMap<>();
        for (Integer percentile : requestedPercentiles) {
            if (percentile == null || percentile < 1 || percentile > 100) {
                throw new IllegalArgumentException("percentile must be between 1 and 100");
            }
            int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
            percentiles.put(percentile, sorted.get(rank - 1));
        }

        int trimCount = (int) Math.floor(sorted.size() * trimPercent / 100.0);
        List<Long> trimmed = trimCount == 0
                ? sorted
                : sorted.subList(trimCount, sorted.size() - trimCount);

        return new TimingStatistics(
                sorted.getFirst(),
                sorted.getLast(),
                average(sorted),
                average(trimmed),
                percentiles);
    }

    private static double average(List<Long> values) {
        return values.stream().mapToDouble(Long::doubleValue).average().orElseThrow();
    }
}
