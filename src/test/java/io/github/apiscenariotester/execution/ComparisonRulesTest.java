package io.github.apiscenariotester.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.apiscenariotester.http.CurlResponse;
import org.junit.jupiter.api.Test;

class ComparisonRulesTest {
    @Test void rulesHandleNewlinesCaseWildcardsRecursiveFiltersMissingPathsAndPrecision() throws Exception {
        var rules = ComparisonRules.compile(" DATE\r\n\nX-ID\nDate\n", "$..timestamp\r\n$.users[*].updatedAt\n" +
                "$.users[?(@.id == 2)].secret\n$['odd.key']\n$.missing\n");
        assertThat(rules.skipsHeader("date")).isTrue(); assertThat(rules.skipsHeader("x-id")).isTrue();
        assertThat(rules.skipsHeader("content-type")).isFalse();
        String body = "{\"timestamp\":1,\"nested\":{\"timestamp\":2},\"users\":[{\"id\":1,\"updatedAt\":1}," +
                "{\"id\":2,\"updatedAt\":2,\"secret\":\"hide\"}],\"odd.key\":4,\"precise\":0.12345678901234567890123456789}";
        String filtered = rules.filterBody(body);
        assertThat(filtered).doesNotContain("timestamp", "updatedAt", "secret", "odd.key")
                .contains("0.12345678901234567890123456789");
        assertThat(new ObjectMapper().readTree(filtered).at("/users/1/id").asInt()).isEqualTo(2);
    }

    @Test void arraySelectionsAreResolvedBeforeDeletionToPreventIndexShifts() throws Exception {
        var rules = ComparisonRules.compile("", "$[0]\n$[1]\n$[10]\n$[11]\n");
        String body = "[0,1,2,3,4,5,6,7,8,9,10,11]";
        assertThat(new ObjectMapper().readTree(rules.filterBody(body))).isEqualTo(new ObjectMapper().readTree("[2,3,4,5,6,7,8,9]"));
        var overlap = ComparisonRules.compile("", "$.items[0].secret\n$.items[0]\n$.items[1].secret");
        assertThat(new ObjectMapper().readTree(overlap.filterBody("{\"items\":[{\"secret\":1},{\"secret\":2,\"keep\":3}]}")))
                .isEqualTo(new ObjectMapper().readTree("{\"items\":[{\"keep\":3}]}"));
    }

    @Test void rootAndNonJsonRulesBehaveWithoutChangingRawResponses() {
        assertThat(ComparisonRules.compile("", "$").filterBody("{\"a\":1}")).isEmpty();
        var rules = ComparisonRules.compile("", "$.a\n$.missing");
        for (String body : new String[] {"plain\r\n", "", "{\"a\":", "{\"a\":1} extra"})
            assertThat(rules.filterBody(body)).isEqualTo(body);
        var base = response("Date: first\r\nX-Keep: a\r\n", "{\"a\":1,\"keep\":5}");
        var reference = response("date: second\r\nX-Keep: b\r\n", "{\"a\":2,\"keep\":6}");
        var comparison = ReferenceComparison.compare("http://reference", base, reference, ComparisonRules.compile("Date", "$.a"));
        assertThat(comparison.headersMatch()).isFalse(); assertThat(comparison.responseMatch()).isFalse();
        assertThat(comparison.detail()).contains("x-keep").doesNotContain("headers differ: [date");
        assertThat(comparison.response()).isEqualTo(reference); assertThat(base.body()).contains("\"a\":1");
    }

    @Test void invalidRulesFailEarlyButQuotedBracketsAndRegexFiltersAreValid() {
        for (String path : new String[] {"$.users[", "$.users[?(@.id == 1]", "$['bad]", "not-a-path"})
            assertThatThrownBy(() -> ComparisonRules.compile("", path)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compareSkipBody");
        assertThatThrownBy(() -> ComparisonRules.compile("X-Bad Header", "")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compareSkipHeader");
        assertThat(ComparisonRules.compile("", "$['a[b]']\n$.items[?(@.name =~ /a'\\[/)]")).isNotNull();
    }

    @Test void excludingOneFieldPreservesOtherDuplicateFieldsAndNumericSpelling() {
        var rules = ComparisonRules.compile("", "$.timestamp");
        String body = "{\"timestamp\":1,\"duplicate\":1,\"duplicate\":2,\"number\":1e3}";
        assertThat(rules.filterBody(body)).contains("\"duplicate\":1", "\"duplicate\":2", "1e3").doesNotContain("timestamp");
    }

    @Test void recursiveSelectionHandlesQuotedAndBackslashPropertyNames() {
        var rules = ComparisonRules.compile("", "$..skip");
        assertThat(rules.filterBody("{\"a'b\":{\"skip\":1,\"keep\":2},\"a\\\\b\":{\"skip\":3,\"keep\":4}}"))
                .isEqualTo("{\"a'b\":{\"keep\":2},\"a\\\\b\":{\"keep\":4}}");
    }

    @Test void propertyUnionsAndNegativeArrayIndicesExcludeOriginalNodes() {
        assertThat(ComparisonRules.compile("", "$['a','b','missing']\n$.items[-1]\n$.items[0,1]")
                .filterBody("{\"a\":1,\"b\":2,\"keep\":3,\"items\":[0,1,2,3,4]}"))
                .isEqualTo("{\"keep\":3,\"items\":[2,3]}");
    }

    @Test void ambiguousLibraryPathsBecomeComparisonErrorsWithoutExcludingUnselectedFields() {
        var rules = ComparisonRules.compile("", "$.a.skip");
        String body = "{\"a\":{\"skip\":1,\"keep\":2},\"a']['skip\":3}";
        assertThatThrownBy(() -> rules.filterBody(body)).hasMessageContaining("ambiguous");
        var reference = response("", body);
        var result = ReferenceComparison.compare("http://reference", response("", body), reference, rules);
        assertThat(result.responseMatch()).isFalse();
        assertThat(result.detail()).contains("body comparison", "ambiguous");
        assertThat(result.response()).isSameAs(reference);
        assertThat(reference.body()).isEqualTo(body);
    }

    private static CurlResponse response(String headers, String body) { return new CurlResponse(200, 0, 1, headers, body, "", null); }
}
