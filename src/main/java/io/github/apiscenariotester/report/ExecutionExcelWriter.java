package io.github.apiscenariotester.report;

import io.github.apiscenariotester.execution.CallResult;
import io.github.apiscenariotester.execution.ScenarioRunPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExecutionExcelWriter {
    private static final List<String> COLUMNS = List.of("iteration", "order", "name", "method", "url", "status",
            "curlExitCode", "elapsedMs", "success", "error", "curlLog");

    public void write(List<CallResult> calls, ScenarioRunPlan plan, Path output) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont(); bold.setBold(true); headerStyle.setFont(bold);
            CellStyle linkStyle = workbook.createCellStyle();
            Font blue = workbook.createFont(); blue.setColor(IndexedColors.BLUE.getIndex()); blue.setUnderline(Font.U_SINGLE);
            linkStyle.setFont(blue);
            CellStyle timingStyle = workbook.createCellStyle();
            timingStyle.setDataFormat(workbook.createDataFormat().getFormat("0.000"));

            Sheet summary = workbook.createSheet("summary");
            header(summary, List.of("metric", "value"), headerStyle);
            metric(summary, "totalCalls", calls.size());
            long succeeded = calls.stream().filter(CallResult::success).count();
            metric(summary, "succeeded", succeeded);
            metric(summary, "failed", calls.size() - succeeded);
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
                    switch (COLUMNS.get(index)) { case "url", "error" -> 65 * 256; case "name", "curlLog" -> 42 * 256; default -> 16 * 256; });
            for (CallResult call : calls) {
                var row = sheet.createRow(sheet.getLastRowNum() + 1);
                row.createCell(0).setCellValue(call.iteration()); row.createCell(1).setCellValue(call.step().order());
                text(row.createCell(2), call.step().name()); text(row.createCell(3), call.step().request().method());
                text(row.createCell(4), call.step().request().url()); row.createCell(5).setCellValue(call.response().status());
                row.createCell(6).setCellValue(call.response().exitCode());
                Cell elapsed = row.createCell(7); elapsed.setCellValue(call.response().elapsedMs()); elapsed.setCellStyle(timingStyle);
                row.createCell(8).setCellValue(call.success()); text(row.createCell(9), call.error());
                Cell log = row.createCell(10); log.setCellValue("");
                if (call.response().logFile() != null) {
                    var hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.FILE);
                    // The report is built as a sibling temporary file, so its parent is the final report parent.
                    String address = output.toAbsolutePath().normalize().getParent().relativize(call.response().logFile().toAbsolutePath().normalize()).toString().replace('\\', '/');
                    hyperlink.setAddress(address); log.setHyperlink(hyperlink); log.setCellValue(address); log.setCellStyle(linkStyle);
                }
            }
            workbook.write(stream);
        }
    }

    private static void header(Sheet sheet, List<String> names, CellStyle style) {
        var row = sheet.createRow(0);
        for (int index = 0; index < names.size(); index++) { Cell cell = row.createCell(index); cell.setCellValue(names.get(index)); cell.setCellStyle(style); }
        sheet.createFreezePane(0, 1);
    }

    private static void metric(Sheet sheet, String name, double value) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1); row.createCell(0).setCellValue(name); row.createCell(1).setCellValue(value);
    }

    private static void text(Cell cell, String value) {
        cell.setCellValue(value.length() <= 32767 ? value : value.substring(0, 32767));
    }
}
