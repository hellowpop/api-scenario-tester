package io.github.apiscenariotester.conversion.jmeter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.ConvertedScenario;
import io.github.apiscenariotester.input.excel.ScenarioTemplateWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class JMeterScenarioWriter {

    public void write(ConversionResult conversion, Path output) throws IOException {
        Path warnings = warningPath(output);
        if (!conversion.warnings().isEmpty() && Files.exists(warnings)) {
            throw new java.nio.file.FileAlreadyExistsException(warnings.toString());
        }
        new ScenarioTemplateWriter().write(output);
        XSSFWorkbook workbook;
        try (InputStream stream = Files.newInputStream(output)) {
            workbook = new XSSFWorkbook(stream);
        }
        try (workbook; OutputStream stream = Files.newOutputStream(output, StandardOpenOption.TRUNCATE_EXISTING)) {
            Sheet common = workbook.getSheet("common");
            applyCommon(common, conversion.commonValues());
            Map<String, String> hosts = writeHosts(common, conversion);
            writeScenarios(workbook.getSheet("main_scenarios"), conversion, hosts);
            workbook.write(stream);
        }
        if (!conversion.warnings().isEmpty()) {
            try (OutputStream stream = Files.newOutputStream(warnings, StandardOpenOption.CREATE_NEW)) {
                new ObjectMapper(new YAMLFactory()).writeValue(stream, Map.of("warnings", conversion.warnings()));
            }
        }
    }

    private static void applyCommon(Sheet sheet, Map<String, String> values) {
        for (Row row : sheet) {
            if (row.getRowNum() > 0 && values.containsKey(row.getCell(0).getStringCellValue())) {
                row.getCell(1).setCellValue(values.get(row.getCell(0).getStringCellValue()));
            }
        }
    }

    private static Map<String, String> writeHosts(Sheet sheet, ConversionResult conversion) {
        Map<String, String> hosts = new LinkedHashMap<>();
        for (ConvertedScenario scenario : conversion.scenarios()) {
            hosts.computeIfAbsent(scenario.baseUrl(), ignored -> "jmeter" + (hosts.isEmpty() ? "" : "-" + (hosts.size() + 1)));
        }
        hosts.forEach((baseUrl, host) -> {
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            row.createCell(0).setCellValue("host." + host + ".baseUrl");
            row.createCell(1).setCellValue(baseUrl);
        });
        return hosts;
    }

    private static void writeScenarios(Sheet sheet, ConversionResult conversion, Map<String, String> hosts) {
        for (ConvertedScenario scenario : conversion.scenarios()) {
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int column = 0; column < 15; column++) row.createCell(column).setCellValue("");
            row.getCell(0).setCellValue(scenario.order());
            row.getCell(1).setCellValue(scenario.name());
            row.getCell(2).setCellValue(true);
            row.getCell(3).setCellValue(hosts.get(scenario.baseUrl()));
            row.getCell(4).setCellValue(scenario.method());
            row.getCell(5).setCellValue(scenario.path());
            row.getCell(8).setCellValue(scenario.headersJson());
            row.getCell(9).setCellValue(scenario.body());
            row.getCell(10).setCellValue("200-299");
        }
    }

    public static Path warningPath(Path output) {
        String name = output.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return output.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".warnings.yml");
    }
}
