package io.github.apiscenariotester.conversion.jmeter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.apiscenariotester.conversion.ConversionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JMeterTestPlanReaderTest {

    @TempDir Path tempDirectory;

    @Test
    void readsThreadSettingsTimerSamplerHeadersBodyAndWarnings() throws Exception {
        Path input = tempDirectory.resolve("plan.jmx");
        Files.writeString(input, jmx());

        ConversionResult result = new JMeterTestPlanReader().read(input);

        assertThat(result.commonValues()).containsEntry("sessions", "3")
                .containsEntry("iterations", "2")
                .containsEntry("waitPattern", "RANDOM_RANGE")
                .containsEntry("waitMinMs", "100")
                .containsEntry("waitMaxMs", "300");
        assertThat(result.scenarios()).hasSize(2);
        assertThat(result.scenarios().get(0).name()).isEqualTo("Get users");
        assertThat(result.scenarios().get(0).baseUrl()).isEqualTo("https://api.example.com:8443");
        assertThat(result.scenarios().get(0).path()).isEqualTo("/users/${userId}");
        assertThat(result.scenarios().get(0).headersJson()).isEqualTo("{\"Accept\":\"application/json\"}");
        assertThat(result.scenarios().get(1).method()).isEqualTo("POST");
        assertThat(result.scenarios().get(1).body()).isEqualTo("{\"name\":\"sample\"}");
        assertThat(result.warnings()).containsExactly("JSONPostProcessor is not converted: token extractor");
    }

    private static String jmx() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2"><hashTree><TestPlan testname="Sample"/><hashTree>
                <ThreadGroup testname="Users"><stringProp name="ThreadGroup.num_threads">3</stringProp>
                  <elementProp name="ThreadGroup.main_controller"><stringProp name="LoopController.loops">2</stringProp></elementProp>
                </ThreadGroup><hashTree>
                  <UniformRandomTimer testname="Think"><stringProp name="ConstantTimer.delay">100</stringProp><stringProp name="RandomTimer.range">200</stringProp></UniformRandomTimer><hashTree/>
                  <HTTPSamplerProxy testname="Get users"><stringProp name="HTTPSampler.domain">api.example.com</stringProp><stringProp name="HTTPSampler.port">8443</stringProp><stringProp name="HTTPSampler.protocol">https</stringProp><stringProp name="HTTPSampler.path">/users/${userId}</stringProp><stringProp name="HTTPSampler.method">GET</stringProp></HTTPSamplerProxy><hashTree>
                    <HeaderManager testname="Headers"><collectionProp name="HeaderManager.headers"><elementProp><stringProp name="Header.name">Accept</stringProp><stringProp name="Header.value">application/json</stringProp></elementProp></collectionProp></HeaderManager><hashTree/>
                  </hashTree>
                  <HTTPSamplerProxy testname="Create user"><boolProp name="HTTPSampler.postBodyRaw">true</boolProp><stringProp name="HTTPSampler.domain">api.example.com</stringProp><stringProp name="HTTPSampler.protocol">https</stringProp><stringProp name="HTTPSampler.path">/users</stringProp><stringProp name="HTTPSampler.method">POST</stringProp><elementProp name="HTTPsampler.Arguments"><collectionProp><elementProp><stringProp name="Argument.value">{&quot;name&quot;:&quot;sample&quot;}</stringProp></elementProp></collectionProp></elementProp></HTTPSamplerProxy><hashTree>
                    <JSONPostProcessor testname="token extractor"/><hashTree/>
                  </hashTree>
                </hashTree></hashTree></hashTree></jmeterTestPlan>
                """;
    }
}
