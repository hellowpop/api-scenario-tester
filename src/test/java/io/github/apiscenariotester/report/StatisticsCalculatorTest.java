package io.github.apiscenariotester.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticsCalculatorTest {

    private final StatisticsCalculator calculator = new StatisticsCalculator();

    @Test
    void calculatesTrimmedMeanAndNearestRankPercentiles() {
        TimingStatistics result = calculator.calculate(
                List.of(10L, 20L, 30L, 40L, 1000L), 20, List.of(50, 90, 99));

        assertThat(result.minMs()).isEqualTo(10);
        assertThat(result.maxMs()).isEqualTo(1000);
        assertThat(result.averageMs()).isEqualTo(220.0);
        assertThat(result.trimmedAverageMs()).isEqualTo(30.0);
        assertThat(result.percentiles())
                .containsExactlyEntriesOf(java.util.Map.of(50, 30L, 90, 1000L, 99, 1000L));
    }

    @Test
    void usesWholeSampleWhenTrimWouldRemoveNoValues() {
        TimingStatistics result = calculator.calculate(List.of(10L, 20L), 20, List.of(50));

        assertThat(result.averageMs()).isEqualTo(15.0);
        assertThat(result.trimmedAverageMs()).isEqualTo(15.0);
        assertThat(result.percentiles()).containsEntry(50, 10L);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(List.of(), 5, List.of(50)))
                .withMessageContaining("sample");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(List.of(10L), 50, List.of(50)))
                .withMessageContaining("trim");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(List.of(10L), -1, List.of(50)))
                .withMessageContaining("trim");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(List.of(-1L), 5, List.of(50)))
                .withMessageContaining("non-negative");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(List.of(10L), 5, List.of(0)))
                .withMessageContaining("percentile");
    }
}
