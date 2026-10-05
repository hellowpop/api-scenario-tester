package io.github.apiscenariotester.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.apiscenariotester.execution.CallResult;
import io.github.apiscenariotester.execution.ReferenceComparison;
import io.github.apiscenariotester.execution.ScenarioRunPlan;
import io.github.apiscenariotester.execution.ScenarioRunPlanReader;
import io.github.apiscenariotester.http.CurlRequest;
import io.github.apiscenariotester.http.CurlResponse;
import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioYamlCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionExcelWriterTest {
    @TempDir Path directory;

    @Test void shortAnd4096CharacterResponsesRemainInlineWithoutBigDirectory() throws Exception {
        String headers = "HTTP/1.1 200 OK\r\nX-Test: 한글\r\n\r\n";
        String body = "😀".repeat(4096);
        var base = response(headers, body);
        var reference = response(headers, body);
        Path output = directory.resolve("inline.xlsx");
        new ExecutionExcelWriter().write(List.of(call(base, ReferenceComparison.compare("http://reference/", base, reference))), plan(), output);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet sheet = workbook.getSheet("calls"); Row row = sheet.getRow(1);
            for (String column : List.of("responseHeaders", "referenceResponseHeaders")) {
                assertThat(cell(sheet, row, column).getStringCellValue()).isEqualTo(headers);
                assertThat(cell(sheet, row, column).getHyperlink()).isNull();
            }
            for (String column : List.of("responseBody", "referenceResponseBody")) {
                assertThat(cell(sheet, row, column).getStringCellValue()).isEqualTo(body);
                assertThat(cell(sheet, row, column).getHyperlink()).isNull();
            }
        }
        assertThat(directory.resolve("big")).doesNotExist();
    }

    @Test void oversizedResponsesBecomeDistinctRelativeLinksWithCompleteUtf8Contents() throws Exception {
        String headers = "x".repeat(4097), body = "한글\r\n".repeat(10000);
        var base = response(headers, body);
        var reference = response(headers, body);
        Path output = directory.resolve("large.xlsx");
        var calls = List.of(call(base, ReferenceComparison.compare("http://reference/", base, reference)));
        var plan = plan(); var writer = new ExecutionExcelWriter();
        writer.write(calls, plan, output);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet sheet = workbook.getSheet("calls"); Row row = sheet.getRow(1);
            for (String column : List.of("responseHeaders", "responseBody", "referenceResponseHeaders", "referenceResponseBody")) {
                Cell cell = cell(sheet, row, column);
                assertThat(cell.getHyperlink().getType()).isEqualTo(HyperlinkType.FILE);
                String address = cell.getHyperlink().getAddress();
                assertThat(address).matches("big/[0-9a-f-]{36}\\.txt"); UUID.fromString(address.substring(4, address.length() - 4));
                assertThat(cell.getStringCellValue()).isEqualTo(address);
                assertThat(Files.readString(directory.resolve(address))).isEqualTo(column.endsWith("Headers") ? headers : body);
            }
        }
        writer.write(calls, plan, directory.resolve("second.xlsx"));
        try (var files = Files.list(directory.resolve("big"))) { assertThat(files.count()).isEqualTo(8); }
    }

    @Test void absentReferenceIsBlankAndEmptyResponsesStayInline() throws Exception {
        Path output = directory.resolve("empty.xlsx");
        new ExecutionExcelWriter().write(List.of(call(response("", ""), null)), plan(), output);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet sheet = workbook.getSheet("calls"); Row row = sheet.getRow(1);
            for (String column : List.of("responseHeaders", "responseBody", "referenceResponseHeaders", "referenceResponseBody")) {
                assertThat(cell(sheet, row, column).getStringCellValue()).isEmpty();
                assertThat(cell(sheet, row, column).getHyperlink()).isNull();
            }
        }
        assertThat(directory.resolve("big")).doesNotExist();
    }

    @Test void jsonBodiesArePrettyPrintedForBothServersWithoutChangingRawResponse() throws Exception {
        String body = "{\"name\":\"한글\",\"list\":[{\"id\":1},null,true],\"precise\":0.12345678901234567890123456789,\"duplicate\":1,\"duplicate\":2}";
        var base = response("raw headers", body); var comparison = ReferenceComparison.compare("http://reference/", base, base);
        Path output = directory.resolve("pretty.xlsx");
        new ExecutionExcelWriter().write(List.of(call(base, comparison)), plan(), output);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet sheet = workbook.getSheet("calls"); Row row = sheet.getRow(1);
            for (String column : List.of("responseBody", "referenceResponseBody")) {
                String pretty = cell(sheet, row, column).getStringCellValue();
                assertThat(pretty).contains("\n", "  \"name\"", "0.12345678901234567890123456789", "\"duplicate\" : 1", "\"duplicate\" : 2");
                assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(pretty))
                        .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body));
                assertThat(cell(sheet, row, column).getHyperlink()).isNull();
            }
            assertThat(cell(sheet, row, "responseHeaders").getStringCellValue()).isEqualTo("raw headers");
        }
        assertThat(base.body()).isEqualTo(body); assertThat(comparison.response().body()).isEqualTo(body);
    }

    @Test void prettyPrintedLengthDeterminesBigFileThreshold() throws Exception {
        String body = "{\"items\":[" + "1,".repeat(999) + "1]}";
        assertThat(body.length()).isLessThanOrEqualTo(4096);
        Path output = directory.resolve("pretty-big.xlsx");
        new ExecutionExcelWriter().write(List.of(call(response("", body), null)), plan(), output);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Sheet sheet = workbook.getSheet("calls");
            Cell cell = cell(sheet, sheet.getRow(1), "responseBody");
            assertThat(cell.getHyperlink()).isNotNull();
            String pretty = Files.readString(directory.resolve(cell.getHyperlink().getAddress()));
            assertThat(pretty).contains("\n"); assertThat(pretty.length()).isGreaterThan(4096);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(pretty))
                    .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body));
        }
    }

    @Test void nonJsonMalformedJsonAndTrailingContentArePreserved() throws Exception {
        for (String body : List.of("not-json\r\n", "{\"a\":", "{\"a\":1} trailing", "{\"a\":1}{\"b\":2}")) {
            Path output = directory.resolve(UUID.randomUUID() + ".xlsx");
            new ExecutionExcelWriter().write(List.of(call(response("", body), null)), plan(), output);
            try (var workbook = new XSSFWorkbook(Files.newInputStream(output))) {
                Sheet sheet = workbook.getSheet("calls");
                assertThat(cell(sheet, sheet.getRow(1), "responseBody").getStringCellValue()).isEqualTo(body);
            }
        }
    }

    private ScenarioRunPlan plan() throws Exception {
        Path input = directory.resolve(UUID.randomUUID() + ".yml");
        new ScenarioYamlCodec().write(new ScenarioDocument(1, Map.of(), Map.of(), List.of(), List.of(), List.of()), input);
        return new ScenarioRunPlanReader().read(input, null);
    }
    private static CallResult call(CurlResponse response, ReferenceComparison comparison) {
        var request = new CurlRequest("GET", "http://base/", Map.of(), "", 100, 100);
        var step = new ScenarioRunPlan.Step(1, "test", request, 200, 299, null, Map.of(), null);
        return new CallResult(1, 1, step, response, true, "", comparison);
    }
    private static CurlResponse response(String headers, String body) { return new CurlResponse(200, 0, 1, headers, body, "", null); }
    private static Cell cell(Sheet sheet, Row row, String name) {
        for (Cell header : sheet.getRow(0)) if (header.getStringCellValue().equals(name)) return row.getCell(header.getColumnIndex());
        throw new AssertionError("Missing column: " + name);
    }
}
