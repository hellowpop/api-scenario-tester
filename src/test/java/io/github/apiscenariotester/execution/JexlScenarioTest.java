package io.github.apiscenariotester.execution;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.apiscenariotester.scenario.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JexlScenarioTest {
    @TempDir Path directory;
    @Test void stopsBeforeCurlOrAfterFirstCurlEvenWhenContinueOnFailure() throws Exception {
        for (String phase : List.of("PRE", "POST")) {
            var calls = new ArrayList<String>(); var server = server(calls); server.start();
            try {
                var first = new LinkedHashMap<>(row("1","GET","/first","","",""));
                first.put(phase.equals("PRE") ? "preScripts" : "postScripts", "stop");
                var plan = plan(server, List.of(script("stop",phase,"control.stop('requested');")), List.of(first,row("2","GET","/second","","","")),true);
                var results = new ScenarioRunner().run(plan,directory.resolve(phase+".xlsx"),true);
                assertThat(results).hasSize(1); assertThat(results.getFirst().error()).contains("requested");
                assertThat(calls).hasSize(phase.equals("PRE") ? 0 : 1);
            } finally { server.stop(0); }
        }
    }
    @Test void validationAndPostFailuresKeepResponsesAndContinueWithCurlLogs() throws Exception {
        var calls = new ArrayList<String>(); var server = server(calls); server.start();
        try {
            var first = new LinkedHashMap<>(row("1","GET","/first","","","")); first.put("validationScripts","invalid");
            var second = row("2","GET","/second","","broken","");
            var plan = plan(server,List.of(script("invalid","VALIDATE","false"),script("broken","POST","global.missing.field")),List.of(first,second,row("3","GET","/third","","","")),true);
            var results = new ScenarioRunner().run(plan,directory.resolve("continue.xlsx"),true);
            assertThat(results).hasSize(3); assertThat(calls).hasSize(3);
            assertThat(results.get(0).error()).contains("invalid"); assertThat(results.get(1).error()).contains("broken");
            assertThat(results.get(2).success()).isTrue();
            assertThat(results).allMatch(r -> r.response().status() == 200 && java.nio.file.Files.exists(r.response().logFile()));
        } finally { server.stop(0); }
    }
    @Test void separateRunsDoNotShareExtractedGlobals() throws Exception {
        var calls = new ArrayList<String>(); var server = server(calls); server.start();
        try {
            var step = new LinkedHashMap<>(row("1","GET","/one","","mark","")); step.put("validationScripts","fresh");
            var plan = plan(server,List.of(script("fresh","VALIDATE","!global.containsKey('seen')"),script("mark","POST","global.seen=true;")),List.of(step),false);
            for (int run = 0; run < 2; run++) assertThat(new ScenarioRunner().run(plan,directory.resolve("run"+run+".xlsx"),false)).allMatch(CallResult::success);
            assertThat(plan.globals()).isEmpty(); assertThat(calls).hasSize(2);
        } finally { server.stop(0); }
    }
    @Test void failedValidationPreservesStopReasonAndPreventsNextCall() throws Exception {
        var calls = new ArrayList<String>(); var server = server(calls); server.start();
        try {
            var first = new LinkedHashMap<>(row("1","GET","/first","","","")); first.put("validationScripts","stop");
            var plan = plan(server,List.of(script("stop","VALIDATE","control.stop('expired'); false")),List.of(first,row("2","GET","/second","","","")),true);
            var results = new ScenarioRunner().run(plan,directory.resolve("stop-validation.xlsx"),true);
            assertThat(results).hasSize(1); assertThat(calls).hasSize(1);
            assertThat(results.getFirst().error()).contains("validation must return true","execution stopped: expired");
        } finally { server.stop(0); }
    }
    @Test void malformedLaterTemplateIsRejectedBeforeAnyCall() throws Exception {
        var calls = new ArrayList<String>(); var server = server(calls); server.start();
        try {
            var second = new LinkedHashMap<>(row("2","POST","/two","","","")); second.put("body","${global['unclosed']");
            assertThatThrownBy(() -> plan(server,List.of(),List.of(row("1","GET","/one","","",""),second),false)).hasMessageContaining("unclosed template");
            assertThat(calls).isEmpty();
        } finally { server.stop(0); }
    }
    @Test void suppliedExcelSampleExecutesThroughCliAndWritesFourHyperlinks() throws Exception {
        var calls = new ArrayList<String>(); var server = server(calls); server.start();
        try {
            var sample = new ScenarioExcelCodec().read(Path.of("samples/auth/sample-scenario.xlsx"));
            var common = new LinkedHashMap<>(sample.common()); common.put("host.api.baseUrl","http://127.0.0.1:"+server.getAddress().getPort());
            Path input = directory.resolve("sample.yml"), output = directory.resolve("sample-results.xlsx");
            new ScenarioYamlCodec().write(new ScenarioDocument(1,common,sample.resultFormat(),sample.scripts(),sample.subScenarios(),sample.mainScenarios()),input);
            int code = new io.github.apiscenariotester.cli.RootCommand().execute(new java.io.StringWriter(),"run","--scenario",input.toString(),"--output",output.toString(),"--debug");
            assertThat(code).isZero(); assertThat(calls).containsExactly("/api/login","/api/user/list","/api/user/42/get","/api/logout");
            try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(output.toFile())) {
                var sheet = workbook.getSheet("calls"); assertThat(sheet.getLastRowNum()).isEqualTo(4);
                for (int index = 1; index <= 4; index++) assertThat(sheet.getRow(index).getCell(10).getHyperlink()).isNotNull();
            }
        } finally { server.stop(0); }
    }
    @Test void sampleMissingTokenWritesOneFailedCallAndStopsDespiteContinuation() throws Exception {
        var sample = new ScenarioExcelCodec().read(Path.of("samples/auth/sample-scenario.xlsx"));
        for (boolean emptyHeader : List.of(false,true)) {
            var calls = new ArrayList<String>(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange -> {
                calls.add(exchange.getRequestURI().toString());
                if (emptyHeader) exchange.getResponseHeaders().add("X-Token"," ");
                byte[] body = "{}".getBytes(); exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            try {
                var common = new LinkedHashMap<>(sample.common()); common.put("continueOnFailure","true");
                common.put("host.api.baseUrl","http://127.0.0.1:"+server.getAddress().getPort());
                Path input = directory.resolve(UUID.randomUUID()+".yml"), output = directory.resolve(UUID.randomUUID()+".xlsx");
                new ScenarioYamlCodec().write(new ScenarioDocument(1,common,sample.resultFormat(),sample.scripts(),sample.subScenarios(),sample.mainScenarios()),input);
                int code = new io.github.apiscenariotester.cli.RootCommand().execute(new java.io.StringWriter(),"run","--scenario",input.toString(),"--output",output.toString(),"--debug");
                assertThat(code).isEqualTo(1); assertThat(calls).containsExactly("/api/login");
                try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(output.toFile())) {
                    var sheet = workbook.getSheet("calls"); assertThat(sheet.getLastRowNum()).isEqualTo(1);
                    assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).contains("missing a non-empty x-token header");
                    assertThat(sheet.getRow(1).getCell(10).getHyperlink()).isNotNull();
                }
            } finally { server.stop(0); }
        }
    }
    private HttpServer server(List<String> calls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            calls.add(exchange.getRequestURI().toString()); exchange.getResponseHeaders().add("X-Token","secret");
            byte[] body = "{\"serch\":{\"list\":[{\"id\":42}]}}".getBytes(); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); return server;
    }
    private ScenarioRunPlan plan(HttpServer server,List<Map<String,String>> scripts,List<Map<String,String>> rows,boolean continuing) throws Exception {
        Path input = directory.resolve(UUID.randomUUID()+".yml");
        new ScenarioYamlCodec().write(new ScenarioDocument(1,Map.of("host.api.baseUrl","http://127.0.0.1:"+server.getAddress().getPort(),"continueOnFailure",Boolean.toString(continuing)),Map.of(),scripts,List.of(),rows),input);
        return new ScenarioRunPlanReader().read(input,null);
    }
    @Test void loginExtractQueryAndLogoutUseSharedGlobalsAndCurlLogs() throws Exception {
        var calls = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestURI() + ":" + exchange.getRequestHeaders().getFirst("x-token"));
            exchange.getResponseHeaders().add("X-Token", "secret");
            byte[] body = "{\"serch\":{\"list\":[{\"id\":42}]}}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var scripts = List.of(script("token", "POST", "global['x-token'] = response.headers['x-token'][0];"),
                script("user", "POST", "global['user-id'] = response.json['serch']['list'][0]['id'];"),
                script("remove", "POST", "global.remove('x-token');"));
            var sub = List.of(row("1", "POST", "/login", "", "token", "login"), row("1", "POST", "/logout", "{\"x-token\":\"${global['x-token']}\"}", "remove", "logout"));
            var main = List.of(row("1", "SUBSET", "login", "", "", ""), row("2", "GET", "/list", "{\"x-token\":\"${global['x-token']}\"}", "user", ""), row("3", "GET", "/user/${global['user-id']}", "", "", ""), row("4", "SUBSET", "logout", "", "", ""));
            Path input = directory.resolve("scenario.yml");
            new ScenarioYamlCodec().write(new ScenarioDocument(1, Map.of("host.api.baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()), Map.of(), scripts, sub, main), input);
            var results = new ScenarioRunner().run(new ScenarioRunPlanReader().read(input, null), directory.resolve("result.xlsx"), true);
            assertThat(results).hasSize(4).allMatch(CallResult::success);
            assertThat(calls).containsExactly("/login:null", "/list:secret", "/user/42:null", "/logout:secret");
            assertThat(results).allMatch(result -> java.nio.file.Files.exists(result.response().logFile()));
        } finally { server.stop(0); }
    }
    private static Map<String,String> script(String id, String phase, String body) { return Map.of("id",id,"phase",phase,"body",body); }
    private static Map<String,String> row(String order,String method,String path,String headers,String post,String subset) {
        return Map.of("order",order,"name",method+path,"method",method,"path",path,"host","api","headers",headers,"postScripts",post,"subsetId",subset);
    }
}
