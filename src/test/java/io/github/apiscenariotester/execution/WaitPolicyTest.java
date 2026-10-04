package io.github.apiscenariotester.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class WaitPolicyTest {

    @Test
    void fixedPolicyAlwaysReturnsConfiguredDelay() {
        WaitPolicy policy = new WaitPolicy(WaitPattern.FIXED, 250, 250);

        assertThat(policy.nextDelayMillis()).isEqualTo(250);
        assertThat(policy.nextDelayMillis()).isEqualTo(250);
    }

    @Test
    void randomRangeIncludesBothBoundaries() {
        BoundaryRandom random = new BoundaryRandom();
        WaitPolicy policy = new WaitPolicy(WaitPattern.RANDOM_RANGE, 100, 200, random);

        assertThat(policy.nextDelayMillis()).isEqualTo(100);
        assertThat(policy.nextDelayMillis()).isEqualTo(200);
    }

    @Test
    void rejectsInvalidRanges() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WaitPolicy(WaitPattern.FIXED, -1, -1))
                .withMessageContaining("non-negative");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WaitPolicy(WaitPattern.RANDOM_RANGE, 20, 10))
                .withMessageContaining("maximum");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WaitPolicy(WaitPattern.FIXED, 10, 20))
                .withMessageContaining("same");
    }

    private static final class BoundaryRandom implements RandomGenerator {
        private int invocation;

        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long origin, long bound) {
            assertThat(origin).isEqualTo(100);
            assertThat(bound).isEqualTo(201);
            return invocation++ == 0 ? origin : bound - 1;
        }
    }
}
