package io.github.apiscenariotester.report;

import io.github.apiscenariotester.execution.CallResult;
import io.github.apiscenariotester.execution.ScenarioRunPlan;
import io.github.apiscenariotester.http.ResponseBodyFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExecutionExcelWriter {
    private static final List<String> COLUMNS = List.of("session", "iteration", "order", "name", "method", "url", "status",
            "curlExitCode", "elapsedMs", "success", "error", "curlLog", "referenceUrl", "referenceStatus",
            "referenceCurlExitCode", "referenceElapsedMs", "statusMatch", "headersMatch", "responseMatch",
            "comparisonMatch", "comparisonDetail", "referenceCurlLog", "responseHeaders", "responseBody",
            "referenceResponseHeaders", "referenceResponseBody");

    public void write(List<CallResult> calls, ScenarioRunPlan plan, Path output) throws IOException {
        List<Path> createdFiles = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont(); bold.setBold(true); headerStyle.setFont(bold);
            CellStyle linkStyle = workbook.createCellStyle();
            Font blue = workbook.createFont(); blue.setColor(IndexedColors.BLUE.getIndex()); blue.setUnderline(Font.U_SINGLE);
            linkStyle.setFont(blue);
            CellStyle timingStyle = workbook.createCellStyle();
            timingStyle.setDataFormat(workbook.createDataFormat().getFormat("0.000"));
            CellStyle responseStyle = workbook.createCellStyle(); responseStyle.setWrapText(true);

            Sheet summary = workbook.createSheet("summary");
            header(summary, List.of("metric", "value"), headerStyle);
            metric(summary, "sessions", plan.sessions());
            metric(summary, "totalCalls", calls.size());
            long succeeded = calls.stream().filter(CallResult::success).count();
            metric(summary, "succeeded", succeeded);
            metric(summary, "failed", calls.size() - succeeded);
            long compared = calls.stream().filter(call -> call.comparison() != null).count();
            long matched = calls.stream().filter(call -> call.comparison() != null && call.comparison().matches()).count();
            metric(summary, "referenceCalls", calls.stream().filter(call -> call.comparison() != null && call.comparison().requested()).count());
            metric(summary, "comparisonMatched", matched);
            metric(summary, "comparisonUnmatched", compared - matched);
            if (!calls.isEmpty()) {
                var statistics = new StatisticsCalculator().calculate(calls.stream().map(call -> Math.round(call.response().elapsedMs())).toList(), plan.trimPercent(), plan.percentiles());
                metric(summary, "minMs", statistics.minMs()); metric(summary, "maxMs", statistics.maxMs());
                metric(summary, "averageMs", statistics.averageMs()); metric(summary, "trimmedAverageMs", statistics.trimmedAverageMs());
                for (int percentile : plan.percentiles()) metric(summary, "p" + percentile, statistics.percentiles().get(percentile));
            }
            summary.setColumnWidth(0, 24 * 256); summary.setColumnWidth(1, 20 * 256);
            Sheet sheet = workbook.createSheet("calls");
            header(sheet, COLUMNS, headerStyle);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, calls.size(), 0, COLUMNS.size() - 1));
            for (int index = 0; index < COLUMNS.size(); index++) sheet.setColumnWidth(index,
                    switch (COLUMNS.get(index)) { case "url", "referenceUrl", "error", "comparisonDetail", "responseHeaders",
                        "responseBody", "referenceResponseHeaders", "referenceResponseBody" -> 65 * 256;
                        case "name", "curlLog", "referenceCurlLog" -> 42 * 256; default -> 16 * 256; });
            for (CallResult call : calls) {
                var row = sheet.createRow(sheet.getLastRowNum() + 1);
                row.createCell(0).setCellValue(call.session());
                row.createCell(1).setCellValue(call.iteration()); row.createCell(2).setCellValue(call.step().order());
                text(row.createCell(3), call.step().name()); text(row.createCell(4), call.step().request().method());
                text(row.createCell(5), call.step().request().url()); row.createCell(6).setCellValue(call.response().status());
                row.createCell(7).setCellValue(call.response().exitCode());
                Cell elapsed = row.createCell(8); elapsed.setCellValue(call.response().elapsedMs()); elapsed.setCellStyle(timingStyle);
                row.createCell(9).setCellValue(call.success()); text(row.createCell(10), call.error());
                Cell log = row.createCell(11); log.setCellValue("");
                logLink(workbook, output, log, call.response().logFile(), linkStyle);
                for (int index = 12; index < COLUMNS.size(); index++) row.createCell(index).setCellValue("");
                responseText(workbook, output, row.getCell(22), call.response().headers(), linkStyle, responseStyle, createdFiles);
                responseText(workbook, output, row.getCell(23), ResponseBodyFormatter.pretty(call.response().body()), linkStyle, responseStyle, createdFiles);
                var comparison = call.comparison();
                if (comparison != null) {
                    text(row.getCell(12), comparison.url()); row.getCell(13).setCellValue(comparison.response().status());
                    row.getCell(14).setCellValue(comparison.response().exitCode());
                    row.getCell(15).setCellValue(comparison.response().elapsedMs()); row.getCell(15).setCellStyle(timingStyle);
                    row.getCell(16).setCellValue(comparison.statusMatch()); row.getCell(17).setCellValue(comparison.headersMatch());
                    row.getCell(18).setCellValue(comparison.responseMatch()); row.getCell(19).setCellValue(comparison.matches());
                    text(row.getCell(20), comparison.detail());
                    logLink(workbook, output, row.getCell(21), comparison.response().logFile(), linkStyle);
                    responseText(workbook, output, row.getCell(24), comparison.response().headers(), linkStyle, responseStyle, createdFiles);
                    responseText(workbook, output, row.getCell(25), ResponseBodyFormatter.pretty(comparison.response().body()), linkStyle, responseStyle, createdFiles);
                }
            }
            workbook.write(stream);
        } catch (IOException | RuntimeException exception) {
            // Remove only payloads created by this failed report; older reports keep their links.
            for (Path file : createdFiles) {
                try { Files.deleteIfExists(file); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        }
    }

    private static void header(Sheet sheet, List<String> names, CellStyle style) {
        var row = sheet.createRow(0);
        for (int index = 0; index < names.size(); index++) { Cell cell = row.createCell(index); cell.setCellValue(names.get(index)); cell.setCellStyle(style); }
        sheet.createFreezePane(0, 1);
    }

    private static void logLink(XSSFWorkbook workbook, Path output, Cell cell, Path logFile, CellStyle style) {
        if (logFile == null) return;
        var hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.FILE);
        // The temporary report is a sibling of the final report.
        String address = output.toAbsolutePath().normalize().getParent().relativize(logFile.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        hyperlink.setAddress(address); cell.setHyperlink(hyperlink); cell.setCellValue(address); cell.setCellStyle(style);
    }

    private static void responseText(XSSFWorkbook workbook, Path output, Cell cell, String value,
            CellStyle linkStyle, CellStyle responseStyle, List<Path> createdFiles) throws IOException {
        if (value.codePointCount(0, value.length()) <= 4096) {
            cell.setCellValue(value); cell.setCellStyle(responseStyle);
            return;
        }
        Path big = output.toAbsolutePath().normalize().getParent().resolve("big");
        Files.createDirectories(big);
        Path file = Files.createFile(big.resolve(UUID.randomUUID() + ".txt"));
        createdFiles.add(file);
        Files.writeString(file, value, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
        logLink(workbook, output, cell, file, linkStyle);
    }

    private static void metric(Sheet sheet, String name, double value) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1); row.createCell(0).setCellValue(name); row.createCell(1).setCellValue(value);
    }

    private static void text(Cell cell, String value) {
        cell.setCellValue(value.length() <= 32767 ? value : value.substring(0, 32767));
    }
}
