package io.github.apiscenariotester.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.apiscenariotester.cli.RootCommand;
import io.github.apiscenariotester.scenario.ScenarioDocument;
import io.github.apiscenariotester.scenario.ScenarioYamlCodec;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubsetCurlLoggingTest {
    @TempDir Path directory;
    private HttpServer server;
    private final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            calls.add(exchange.getRequestURI().toString());
            byte[] response = ("응답:" + body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Test","subset");
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/fail") ? 500 : 200,response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
    }
    @AfterEach void stopServer() { server.stop(0); }

    @Test void explicitAndPreSubsetsWriteOneLogAndLinkForEveryHttpCallAndIteration() throws Exception {
        var main = new LinkedHashMap<>(row("2","main","GET","/main","")); main.put("preSubsets","prepare");
        var subSecond = new LinkedHashMap<>(row("2","second","POST","/second","second")); subSecond.put("subsetId","prepare");
        var subFirst = new LinkedHashMap<>(row("1","first","POST","/first","first")); subFirst.put("subsetId","prepare");
        Path input = scenario(List.of(subSecond,subFirst),List.of(subset("1","prepare"),main),List.of(),2);
        Path output = directory.resolve("results.xlsx");
        assertThat(run(input,output,true)).isZero();
        assertThat(calls).containsExactly("/first","/second","/first","/second","/main","/first","/second","/first","/second","/main");
        try (var workbook = new XSSFWorkbook(output.toFile())) {
            var sheet = workbook.getSheet("calls"); assertThat(sheet.getLastRowNum()).isEqualTo(10);
            var paths = new ArrayList<Path>();
            for (int index=1; index<=10; index++) {
                var row = sheet.getRow(index); var link = row.getCell(10).getHyperlink();
                assertThat(link).isNotNull(); assertThat(link.getType()).isEqualTo(HyperlinkType.FILE);
                assertThat(link.getAddress()).matches("curl/[0-9a-f-]{36}\\.txt");
                Path log = directory.resolve(link.getAddress()); paths.add(log);
                UUID.fromString(log.getFileName().toString().replace(".txt",""));
                String text = Files.readString(log);
                assertThat(text).contains("COMMAND (argument array)","STDIN (UTF-8)","STDOUT","STDERR","RESPONSE HEADERS","RESPONSE BODY (UTF-8)");
                assertThat(text).contains(row.getCell(4).getStringCellValue(),"X-test: subset");
                String name = row.getCell(2).getStringCellValue();
                if (!name.equals("main")) assertThat(text).contains("STDIN (UTF-8)\n"+name, "응답:"+name);
            }
            assertThat(paths).doesNotHaveDuplicates();
        }
        try (var logs = Files.list(directory.resolve("curl"))) { assertThat(logs.count()).isEqualTo(10); }
    }

    @Test void subsetHttpOrPostFailureRetainsCurlLogAndLinkAndStopsBeforeMain() throws Exception {
        for (boolean postFailure : List.of(false,true)) {
            var sub = new LinkedHashMap<>(row("1","subset-failure","POST",postFailure ? "/post" : "/fail","failure-body"));
            sub.put("subsetId","prepare");
            var scripts = postFailure ? List.of(Map.of("id","broken","phase","POST","body","global.missing.field")) : List.<Map<String,String>>of();
            if (postFailure) sub.put("postScripts","broken");
            Path input = scenario(List.of(sub),List.of(subset("1","prepare"),row("2","main","GET","/main","")),scripts,1);
            Path output = directory.resolve(UUID.randomUUID()+".xlsx");
            calls.clear(); assertThat(run(input,output,true)).isEqualTo(1); assertThat(calls).hasSize(1);
            try (var workbook = new XSSFWorkbook(output.toFile())) {
                var sheet = workbook.getSheet("calls"); assertThat(sheet.getLastRowNum()).isEqualTo(1);
                var result = sheet.getRow(1);
                assertThat(result.getCell(9).getStringCellValue()).contains(postFailure ? "broken" : "received 500");
                var link = result.getCell(10).getHyperlink(); assertThat(link).isNotNull();
                assertThat(Files.readString(directory.resolve(link.getAddress()))).contains("failure-body","응답:failure-body","RESPONSE HEADERS");
            }
        }
    }

    @Test void subsetsFollowTheSameDebugOptionAsMain() throws Exception {
        var sub = new LinkedHashMap<>(row("1","subset","GET","/sub","")); sub.put("subsetId","prepare");
        var main = new LinkedHashMap<>(row("2","main","GET","/main","")); main.put("preSubsets","prepare");
        Path input = scenario(List.of(sub),List.of(subset("1","prepare"),main),List.of(),1), output = directory.resolve("no-debug.xlsx");
        assertThat(run(input,output,false)).isZero(); assertThat(calls).containsExactly("/sub","/sub","/main");
        assertThat(directory.resolve("curl")).doesNotExist();
        try (var workbook = new XSSFWorkbook(output.toFile())) {
            var sheet = workbook.getSheet("calls");
            for (int index=1; index<=3; index++) assertThat(sheet.getRow(index).getCell(10).getHyperlink()).isNull();
        }
    }

    private Path scenario(List<Map<String,String>> subsets,List<Map<String,String>> main,List<Map<String,String>> scripts,int iterations) throws Exception {
        Path input = directory.resolve(UUID.randomUUID()+".yml");
        new ScenarioYamlCodec().write(new ScenarioDocument(1,Map.of("host.api.baseUrl","http://127.0.0.1:"+server.getAddress().getPort(),"iterations",Integer.toString(iterations)),Map.of(),scripts,subsets,main),input);
        return input;
    }
    private int run(Path input,Path output,boolean debug) {
        var console = new StringWriter(); var args = new ArrayList<>(List.of("run","--scenario",input.toString(),"--output",output.toString()));
        if (debug) args.add("--debug"); return new RootCommand().execute(console,args.toArray(String[]::new));
    }
    private static Map<String,String> subset(String order,String id) { return Map.of("order",order,"name","call-"+id,"method","SUBSET","path",id); }
    private static Map<String,String> row(String order,String name,String method,String path,String body) { return Map.of("order",order,"name",name,"method",method,"path",path,"host","api","body",body); }
}
