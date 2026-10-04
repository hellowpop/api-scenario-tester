package io.github.apiscenariotester.script;

import static org.assertj.core.api.Assertions.*;
import io.github.apiscenariotester.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JexlRuntimeTest {
    private static Map<String,String> script(String id,String phase,String body) { return Map.of("id",id,"phase",phase,"body",body); }
    @Test void phasesShareMapsAndTemplatesSupportBracketKeys() {
        var runtime = new JexlRuntime(List.of(script("pre","PRE","global['x-token']='abc'; executor.count=1; request.path='/changed';"),
            script("filter","FILTER","executor.count=executor.count+1;"), script("valid","VALIDATE","executor.count == 2 && response.status == 200"),
            script("post","POST","global.remove('x-token');")));
        var global = new LinkedHashMap<String,Object>(); var context = new ScriptContext();
        context.bind("global",global); context.bind("executor",new LinkedHashMap<>());
        var original = new CurlRequest("GET","http://localhost/${global['missing-id']}",Map.of(),"",100,100);
        var request = runtime.request(original); context.bind("request",request);
        runtime.execute("PRE",List.of("pre"),context);
        assertThat(runtime.render("${global['x-token']}-${1+2}-${ {'a': '}'}['a'] }",context)).isEqualTo("abc-3-}");
        assertThat(runtime.renderRequest(original,request,context).url()).isEqualTo("http://localhost/changed");
        context.bind("response",runtime.response(new CurlResponse(200,0,1,"","plain","",null)));
        runtime.execute("FILTER",List.of("filter"),context); runtime.execute("VALIDATE",List.of("valid"),context);
        runtime.execute("POST",List.of("post"),context); assertThat(global).doesNotContainKey("x-token");
    }
    @Test void responseUsesFinalHeaderBlockAndReadOnlyJson() {
        var runtime = new JexlRuntime(List.of(script("mutate","POST","response.json.a[0]=3;")));
        var response = runtime.response(new CurlResponse(200,0,2,"HTTP/1.1 100 Continue\r\nX-Token: old\r\n\r\nHTTP/1.1 200 OK\r\nX-Token: a\r\nx-token: b\r\n","{\"a\":[1,2]}","",null));
        assertThat(((Map<?,?>)response.get("headers")).containsKey("x-token")).isTrue();
        assertThat(((Map<?,?>)response.get("headers")).get("x-token")).isEqualTo(List.of("a","b"));
        var context = new ScriptContext(); context.bind("response",response);
        assertThatThrownBy(() -> runtime.execute("POST",List.of("mutate"),context)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validationRejectsFalseAndNonBooleanAndReferencesRequireCorrectPhase() {
        for (String body : List.of("false", "1", "null")) {
            var runtime = new JexlRuntime(List.of(script("check","VALIDATE",body)));
            assertThatThrownBy(() -> runtime.execute("VALIDATE",List.of("check"),new ScriptContext())).hasMessageContaining("validation must return true");
            assertThatThrownBy(() -> runtime.validateReferences("POST",List.of("check"))).hasMessageContaining("requires phase");
            assertThatThrownBy(() -> runtime.validateReferences("PRE",List.of("missing"))).hasMessageContaining("unknown script");
        }
    }
    @Test void forbiddenFeaturesAndMalformedTemplatesFailAtCompileTime() {
        for (String body : List.of("new('java.lang.ProcessBuilder')", "while(true) {}", "var f = () -> 1;", "#pragma jexl.import java.lang"))
            assertThatThrownBy(() -> new JexlRuntime(List.of(script("bad","PRE",body)))).isInstanceOf(IllegalArgumentException.class);
        var runtime = new JexlRuntime(List.of());
        for (String text : List.of("${global['missing']", "${}", "${global.x = 1}"))
            assertThatThrownBy(() -> runtime.validateTemplate(text)).isInstanceOf(RuntimeException.class);
    }
    @Test void reflectionAndRootReplacementAreDeniedAndStopHelperIsAvailable() {
        var runtime = new JexlRuntime(List.of(script("reflect","PRE","global.getClass()"),script("replace","PRE","global = {};"),script("stop","PRE","control.stop('done');")));
        var context = new ScriptContext(); context.bind("global",new LinkedHashMap<>()); var control = new ExecutionControl(); context.bind("control",control);
        assertThatThrownBy(() -> runtime.execute("PRE",List.of("reflect"),context)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.execute("PRE",List.of("replace"),context)).isInstanceOf(IllegalArgumentException.class);
        runtime.execute("PRE",List.of("stop"),context); assertThat(control.isStopped()).isTrue(); assertThat(control.getReason()).isEqualTo("done");
    }
    @Test void renderedHeadersRejectControlCharactersBeforeCurl() {
        var runtime = new JexlRuntime(List.of()); var context = new ScriptContext();
        context.bind("global",Map.of("value","bad\r\nInjected: true"));
        var original = new CurlRequest("GET","http://localhost/",Map.of("X-Token","${global.value}"),"",100,100);
        assertThatThrownBy(() -> runtime.renderRequest(original,runtime.request(original),context)).hasMessageContaining("invalid header");
    }
    @Test void duplicateIdsAndInvalidPhasesAreRejected() {
        assertThatThrownBy(() -> new JexlRuntime(List.of(script("same","POST","true"),script("same","POST","true")))).hasMessageContaining("duplicate script");
        assertThatThrownBy(() -> new JexlRuntime(List.of(script("bad","UNKNOWN","true")))).hasMessageContaining("invalid script phase");
    }
    @Test void relativeOverrideOnlyResolvesAuthorityTemplates() {
        var runtime = new JexlRuntime(List.of()); var context = new ScriptContext();
        context.bind("global",Map.of("host/name","localhost:8080"));
        for (String url : List.of("http://${global['host/name']}/users/${global.missing}", "http://localhost:8080?unused=${global.missing}")) {
            var original = new CurlRequest("GET",url,Map.of(),"",100,100); var request = runtime.request(original);
            request.put("path","/login"); assertThat(runtime.renderRequest(original,request,context).url()).isEqualTo("http://localhost:8080/login");
        }
    }
    @Test void missingHeaderErrorIncludesStatusAndHeaderNamesWithoutValues() {
        var runtime = new JexlRuntime(List.of(script("extract-token","POST","global['x-token']=response.headers['x-token'][0];")));
        var context = new ScriptContext(); context.bind("global",new LinkedHashMap<>());
        context.bind("response",runtime.response(new CurlResponse(200,0,1,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Other: private-value\r\n","{}","",null)));
        assertThatThrownBy(() -> runtime.execute("POST",List.of("extract-token"),context))
            .hasMessageContaining("response status=200", "response header names=[content-type, x-other]", "script:extract-token")
            .hasMessageNotContaining("private-value").hasMessageNotContaining("JexlRuntime.<init>");
    }
    @Test void sampleStopsExplicitlyWhenSuccessfulLoginOmitsOrEmptiesToken() throws Exception {
        var document = new io.github.apiscenariotester.scenario.ScenarioExcelCodec().read(java.nio.file.Path.of("samples/auth/sample-scenario.xlsx"));
        var runtime = new JexlRuntime(document.scripts());
        for (String headers : List.of("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n", "HTTP/1.1 200 OK\r\nX-Token: \r\n")) {
            var context = new ScriptContext(); var globals = new LinkedHashMap<String,Object>(); var control = new ExecutionControl();
            context.bind("global",globals); context.bind("control",control);
            context.bind("response",runtime.response(new CurlResponse(200,0,1,headers,"{}","",null)));
            runtime.execute("POST",List.of("extract-token"),context);
            assertThat(control.isStopped()).isTrue(); assertThat(control.getReason()).contains("x-token");
            assertThat(globals).doesNotContainKey("x-token");
        }
    }
}
