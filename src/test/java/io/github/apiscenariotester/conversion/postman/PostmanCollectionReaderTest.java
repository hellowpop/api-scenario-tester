package io.github.apiscenariotester.conversion.postman;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.apiscenariotester.conversion.ConversionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostmanCollectionReaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void convertsNestedRequestsAndReportsUnsupportedScriptsAndAuthentication() throws Exception {
        Path input = tempDirectory.resolve("collection.json");
        Files.writeString(input, """
                {
                  "info": {"name": "Sample", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                  "auth": {"type": "bearer"},
                  "item": [{
                    "name": "Users",
                    "item": [{
                      "name": "Get user",
                      "request": {
                        "method": "GET",
                        "header": [{"key": "X-Tenant", "value": "{{tenantId}}"}],
                        "url": {"raw": "https://api.example.com/users/{{userId}}?verbose=true"}
                      }
                    }, {
                      "name": "Create user",
                      "event": [{"listen": "test", "script": {"exec": ["pm.test('ok', () => {});"]}}],
                      "request": {
                        "method": "POST",
                        "header": [{"key": "Content-Type", "value": "application/json"}],
                        "body": {"mode": "raw", "raw": "{\\\"name\\\":\\\"{{userName}}\\\"}"},
                        "url": {"raw": "https://api.example.com/users"}
                      }
                    }]
                  }]
                }
                """);

        ConversionResult result = new PostmanCollectionReader().read(input);

        assertThat(result.scenarios()).hasSize(2);
        assertThat(result.scenarios().get(0).order()).isEqualTo(1);
        assertThat(result.scenarios().get(0).name()).isEqualTo("Get user");
        assertThat(result.scenarios().get(0).method()).isEqualTo("GET");
        assertThat(result.scenarios().get(0).baseUrl()).isEqualTo("https://api.example.com");
        assertThat(result.scenarios().get(0).path())
                .isEqualTo("/users/${global.userId}?verbose=true");
        assertThat(result.scenarios().get(0).headersJson())
                .isEqualTo("{\"X-Tenant\":\"${global.tenantId}\"}");
        assertThat(result.scenarios().get(1).body())
                .isEqualTo("{\"name\":\"${global.userName}\"}");
        assertThat(result.warnings())
                .containsExactly(
                        "Collection authentication helper is not converted: bearer",
                        "Create user test script is not converted");
    }
}
