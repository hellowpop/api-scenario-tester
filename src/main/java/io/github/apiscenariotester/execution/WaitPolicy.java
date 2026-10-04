package io.github.apiscenariotester.execution;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class WaitPolicy {

    private final WaitPattern pattern;
    private final long minimumMillis;
    private final long maximumMillis;
    private final RandomGenerator random;

    public WaitPolicy(WaitPattern pattern, long minimumMillis, long maximumMillis) {
        this(pattern, minimumMillis, maximumMillis, ThreadLocalRandom.current());
    }

    public WaitPolicy(
            WaitPattern pattern,
            long minimumMillis,
            long maximumMillis,
            RandomGenerator random) {
        this.pattern = Objects.requireNonNull(pattern, "pattern must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        if (minimumMillis < 0 || maximumMillis < 0) {
            throw new IllegalArgumentException("wait values must be non-negative");
        }
        if (maximumMillis < minimumMillis) {
            throw new IllegalArgumentException("maximum wait must be greater than or equal to minimum wait");
        }
        if (pattern == WaitPattern.FIXED && minimumMillis != maximumMillis) {
            throw new IllegalArgumentException("fixed wait minimum and maximum must be the same");
        }
        if (maximumMillis == Long.MAX_VALUE) {
            throw new IllegalArgumentException("maximum wait must be less than Long.MAX_VALUE");
        }
        this.minimumMillis = minimumMillis;
        this.maximumMillis = maximumMillis;
    }

    public long nextDelayMillis() {
        if (pattern == WaitPattern.FIXED || minimumMillis == maximumMillis) {
            return minimumMillis;
        }
        return random.nextLong(minimumMillis, maximumMillis + 1);
    }
}
