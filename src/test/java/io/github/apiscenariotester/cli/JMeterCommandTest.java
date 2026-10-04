package io.github.apiscenariotester.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JMeterCommandTest {

    @TempDir Path tempDirectory;

    @Test
    void convertsJMeterPlanFromCli() throws Exception {
        Path input = tempDirectory.resolve("plan.jmx");
        Files.writeString(input, """
                <jmeterTestPlan><hashTree><TestPlan/><hashTree><ThreadGroup><stringProp name="ThreadGroup.num_threads">1</stringProp></ThreadGroup><hashTree><HTTPSamplerProxy testname="Health"><stringProp name="HTTPSampler.domain">api.example.com</stringProp><stringProp name="HTTPSampler.protocol">https</stringProp><stringProp name="HTTPSampler.path">/health</stringProp><stringProp name="HTTPSampler.method">GET</stringProp></HTTPSamplerProxy><hashTree/></hashTree></hashTree></hashTree></jmeterTestPlan>
                """);
        Path output = tempDirectory.resolve("scenario.xlsx");
        StringWriter console = new StringWriter();

        int code = new RootCommand().execute(console, "convert", "jmeter", "--input", input.toString(), "--output", output.toString());

        assertThat(code).isZero();
        assertThat(console.toString()).contains("Converted 1 request");
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertThat(workbook.getSheet("main_scenarios").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Health");
        }
    }

    @Test
    void rejectsDoctypeInput() throws Exception {
        Path input = tempDirectory.resolve("unsafe.jmx");
        Files.writeString(input, "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><jmeterTestPlan>&e;</jmeterTestPlan>");
        StringWriter console = new StringWriter();

        int code = new RootCommand().execute(console, "convert", "jmeter", "--input", input.toString(), "--output", tempDirectory.resolve("out.xlsx").toString());

        assertThat(code).isEqualTo(2);
        assertThat(console.toString()).contains("Unable to convert JMeter test plan");
    }
}
