package xyz.erupt.langchain4j.mcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class McpHttpHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpHttpHandler handler = McpHttpHandler.of("sample", "1.0", List.of(new SampleServer()));

    @Test
    void should_advertise_tools_prompts_and_resources() throws Exception {
        JsonNode json = call("initialize", "{\"protocolVersion\":\"2025-06-18\"}");

        assertThat(json.at("/result/serverInfo/name").asText()).isEqualTo("sample");
        assertThat(json.at("/result/capabilities").fieldNames()).toIterable()
                .contains("tools", "prompts", "resources");
    }

    @Test
    void should_call_tool() throws Exception {
        JsonNode json = call("tools/call", "{\"name\":\"add\",\"arguments\":{\"a\":1,\"b\":2}}");

        assertThat(json.at("/result/content/0/text").asText()).isEqualTo("3");
    }

    @Test
    void should_list_and_get_prompts() throws Exception {
        JsonNode list = call("prompts/list", "{}");
        assertThat(list.at("/result/prompts/0/name").asText()).isEqualTo("greet");
        assertThat(list.at("/result/prompts/0/arguments/0/name").asText()).isEqualTo("name");
        assertThat(list.at("/result/prompts/0/arguments/1/required").asBoolean()).isFalse();

        JsonNode get = call("prompts/get", "{\"name\":\"greet\",\"arguments\":{\"name\":\"Ann\",\"lang\":\"zh\"}}");
        assertThat(get.at("/result/messages/0/role").asText()).isEqualTo("user");
        assertThat(get.at("/result/messages/0/content/text").asText()).isEqualTo("你好, Ann");
    }

    @Test
    void should_reject_missing_prompt_argument() throws Exception {
        JsonNode json = call("prompts/get", "{\"name\":\"greet\",\"arguments\":{}}");

        assertThat(json.at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    void should_list_and_read_resources() throws Exception {
        JsonNode list = call("resources/list", "{}");
        assertThat(list.at("/result/resources/0/uri").asText()).isEqualTo("docs://guide");
        assertThat(list.at("/result/resources/0/name").asText()).isEqualTo("guide");

        JsonNode read = call("resources/read", "{\"uri\":\"docs://guide\"}");
        assertThat(read.at("/result/contents/0/text").asText()).isEqualTo("# Guide");
        assertThat(read.at("/result/contents/0/mimeType").asText()).isEqualTo("text/markdown");

        assertThat(call("resources/read", "{\"uri\":\"docs://none\"}").at("/error/code").asInt()).isEqualTo(-32002);
    }

    @Test
    void should_answer_ping_and_preserve_string_id() throws Exception {
        McpHttpHandler.Response response = post("{\"jsonrpc\":\"2.0\",\"id\":\"p-1\",\"method\":\"ping\"}", Map.of());

        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        assertThat(json.get("id").asText()).isEqualTo("p-1");
        assertThat(json.get("result").isEmpty()).isTrue();
    }

    @Test
    void should_preserve_string_id_for_tools() throws Exception {
        JsonNode json = OBJECT_MAPPER.readTree(
                post("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"tools/list\"}", Map.of()).body());

        assertThat(json.get("id").asText()).isEqualTo("abc");
        assertThat(json.at("/result/tools/0/name").asText()).isEqualTo("add");
    }

    @Test
    void should_follow_streamable_http_status_codes() {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

        assertThat(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of()).statusCode())
                .isEqualTo(202);
        assertThat(handler.handle("GET", headers(Map.of()), null).headers()).containsEntry("Allow", "POST");
        assertThat(handler.handle("GET", headers(Map.of()), null).statusCode()).isEqualTo(405);
        assertThat(handler.handle("POST", headers(Map.of("Content-Type", "text/plain")), ping).statusCode())
                .isEqualTo(415);
        assertThat(post(ping, Map.of("Accept", "text/html")).statusCode()).isEqualTo(406);
        assertThat(post(ping, Map.of("Accept", "application/json, text/event-stream")).statusCode())
                .isEqualTo(200);
        assertThat(post("{oops", Map.of()).statusCode()).isEqualTo(400);
        assertThat(post("[" + ping + "]", Map.of()).statusCode()).isEqualTo(400);
        assertThat(post(ping, Map.of("Origin", "https://evil.example")).statusCode()).isEqualTo(403);
    }

    private JsonNode call(String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
        McpHttpHandler.Response response = post(body, Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        return OBJECT_MAPPER.readTree(response.body());
    }

    private McpHttpHandler.Response post(String body, Map<String, String> extra) {
        Map<String, String> headers = new HashMap<>(extra);
        headers.putIfAbsent("Content-Type", "application/json");
        return handler.handle("POST", headers(headers), body);
    }

    private static Function<String, String> headers(Map<String, String> headers) {
        return name -> headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
