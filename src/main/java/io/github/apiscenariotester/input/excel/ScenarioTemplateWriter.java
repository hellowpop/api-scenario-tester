package io.github.apiscenariotester.input.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ScenarioTemplateWriter {

    private static final List<String> SUB_SCENARIO_HEADERS = List.of(
            "subsetId", "order", "name", "host", "method", "path", "preScripts",
            "headers", "body", "expectedStatus", "expectedMaxMs", "filterScripts",
            "validationScripts", "postScripts");

    private static final List<String> MAIN_SCENARIO_HEADERS = List.of(
            "order", "name", "enabled", "host", "method", "path", "preSubsets",
            "preScripts", "headers", "body", "expectedStatus", "expectedMaxMs",
            "filterScripts", "validationScripts", "postScripts");

    public void write(Path output) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                OutputStream stream = Files.newOutputStream(
                        output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            createKeyValueSheet(
                    workbook,
                    "common",
                    headerStyle,
                    List.of(
                            List.of("sessions", "1"),
                            List.of("iterations", "1"),
                            List.of("waitPattern", "FIXED"),
                            List.of("waitMinMs", "0"),
                            List.of("waitMaxMs", "0"),
                            List.of("continueOnFailure", "false")));
            createKeyValueSheet(
                    workbook,
                    "result_format",
                    headerStyle,
                    List.of(
                            List.of("output", "results.xlsx"),
                            List.of("includeCallDetails", "true"),
                            List.of("percentiles", "50,90,95,99"),
                            List.of("trimPercent", "5")));
            createSheet(workbook, "scripts", headerStyle, List.of("id", "phase", "body"));
            createSheet(workbook, "sub_scenarios", headerStyle, SUB_SCENARIO_HEADERS);
            createSheet(workbook, "main_scenarios", headerStyle, MAIN_SCENARIO_HEADERS);
            workbook.write(stream);
        }
    }

    private static CellStyle createHeaderStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private static void createKeyValueSheet(
            XSSFWorkbook workbook,
            String name,
            CellStyle headerStyle,
            List<List<String>> values) {
        Sheet sheet = createSheet(workbook, name, headerStyle, List.of("key", "value"));
        for (int index = 0; index < values.size(); index++) {
            Row row = sheet.createRow(index + 1);
            row.createCell(0).setCellValue(values.get(index).get(0));
            row.createCell(1).setCellValue(values.get(index).get(1));
        }
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    private static Sheet createSheet(
            XSSFWorkbook workbook, String name, CellStyle headerStyle, List<String> headers) {
        Sheet sheet = workbook.createSheet(name);
        Row headerRow = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = headerRow.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, Math.max(12, headers.get(index).length() + 2) * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }
}
