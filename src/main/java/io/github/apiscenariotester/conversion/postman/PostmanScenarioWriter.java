package io.github.apiscenariotester.conversion.postman;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.apiscenariotester.conversion.ConversionResult;
import io.github.apiscenariotester.conversion.ConvertedScenario;
import io.github.apiscenariotester.input.excel.ScenarioTemplateWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class PostmanScenarioWriter {

    private final ScenarioTemplateWriter templateWriter = new ScenarioTemplateWriter();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public void write(ConversionResult conversion, Path output) throws IOException {
        Path warningsOutput = warningPath(output);
        if (!conversion.warnings().isEmpty() && Files.exists(warningsOutput)) {
            throw new FileAlreadyExistsException(warningsOutput.toString());
        }
        templateWriter.write(output);
        populateWorkbook(conversion, output);
        if (!conversion.warnings().isEmpty()) {
            try (OutputStream stream = Files.newOutputStream(
                    warningsOutput, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                yamlMapper.writeValue(stream, Map.of("warnings", conversion.warnings()));
            }
        }
    }

    private static void populateWorkbook(ConversionResult conversion, Path output) throws IOException {
        XSSFWorkbook workbook;
        try (InputStream stream = Files.newInputStream(output)) {
            workbook = new XSSFWorkbook(stream);
        }
        try (workbook;
                OutputStream stream = Files.newOutputStream(
                        output, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            Map<String, String> hosts = writeHosts(workbook.getSheet("common"), conversion);
            writeScenarios(workbook.getSheet("main_scenarios"), conversion, hosts);
            workbook.write(stream);
        }
    }

    private static Map<String, String> writeHosts(Sheet common, ConversionResult conversion) {
        Map<String, String> hosts = new LinkedHashMap<>();
        for (ConvertedScenario scenario : conversion.scenarios()) {
            hosts.computeIfAbsent(scenario.baseUrl(), ignored -> "postman" + (hosts.isEmpty() ? "" : "-" + (hosts.size() + 1)));
        }
        for (Map.Entry<String, String> host : hosts.entrySet()) {
            Row row = common.createRow(common.getLastRowNum() + 1);
            row.createCell(0).setCellValue("host." + host.getValue() + ".baseUrl");
            row.createCell(1).setCellValue(host.getKey());
        }
        return hosts;
    }

    private static void writeScenarios(
            Sheet sheet, ConversionResult conversion, Map<String, String> hosts) {
        for (ConvertedScenario scenario : conversion.scenarios()) {
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int column = 0; column < 15; column++) {
                row.createCell(column).setCellValue("");
            }
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
        String fileName = output.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String baseName = extension > 0 ? fileName.substring(0, extension) : fileName;
        return output.resolveSibling(baseName + ".warnings.yml");
    }
}
