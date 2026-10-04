package io.github.apiscenariotester.scenario;

import io.github.apiscenariotester.input.excel.ScenarioTemplateWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ScenarioExcelCodec {

    public ScenarioDocument read(Path input) throws IOException {
        try (InputStream stream = Files.newInputStream(input);
                XSSFWorkbook workbook = new XSSFWorkbook(stream)) {
            return new ScenarioDocument(
                    1,
                    readKeyValues(workbook.getSheet("common")),
                    readKeyValues(workbook.getSheet("result_format")),
                    readRows(workbook.getSheet("scripts")),
                    readRows(workbook.getSheet("sub_scenarios")),
                    readRows(workbook.getSheet("main_scenarios")));
        }
    }

    public void write(ScenarioDocument document, Path output) throws IOException {
        new ScenarioTemplateWriter().write(output);
        XSSFWorkbook workbook;
        try (InputStream stream = Files.newInputStream(output)) {
            workbook = new XSSFWorkbook(stream);
        }
        try (workbook; OutputStream stream = Files.newOutputStream(output, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeKeyValues(workbook.getSheet("common"), document.common());
            writeKeyValues(workbook.getSheet("result_format"), document.resultFormat());
            writeRows(workbook.getSheet("scripts"), document.scripts());
            writeRows(workbook.getSheet("sub_scenarios"), document.subScenarios());
            writeRows(workbook.getSheet("main_scenarios"), document.mainScenarios());
            workbook.write(stream);
        }
    }

    private static Map<String, String> readKeyValues(Sheet sheet) {
        Map<String, String> values = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) continue;
            String key = cellValue(row.getCell(0), formatter);
            if (!key.isBlank()) values.put(key, cellValue(row.getCell(1), formatter));
        }
        return values;
    }

    private static List<Map<String, String>> readRows(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        Row header = sheet.getRow(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < header.getLastCellNum(); column++) {
                String value = cellValue(row.getCell(column), formatter);
                if (!value.isBlank()) values.put(cellValue(header.getCell(column), formatter), value);
            }
            if (!values.isEmpty()) rows.add(values);
        }
        return rows;
    }

    private static void writeKeyValues(Sheet sheet, Map<String, String> values) {
        clearDataRows(sheet);
        int index = 1;
        for (Map.Entry<String, String> value : values.entrySet()) {
            Row row = sheet.createRow(index++);
            row.createCell(0).setCellValue(value.getKey());
            row.createCell(1).setCellValue(value.getValue());
        }
    }

    private static void writeRows(Sheet sheet, List<Map<String, String>> values) {
        clearDataRows(sheet);
        Row header = sheet.getRow(0);
        int rowIndex = 1;
        for (Map<String, String> source : values) {
            Row row = sheet.createRow(rowIndex++);
            for (int column = 0; column < header.getLastCellNum(); column++) {
                String key = header.getCell(column).getStringCellValue();
                row.createCell(column).setCellValue(source.getOrDefault(key, ""));
            }
        }
    }

    private static void clearDataRows(Sheet sheet) {
        for (int index = sheet.getLastRowNum(); index >= 1; index--) {
            Row row = sheet.getRow(index);
            if (row != null) sheet.removeRow(row);
        }
    }

    private static String cellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
        return formatter.formatCellValue(cell);
    }
}
