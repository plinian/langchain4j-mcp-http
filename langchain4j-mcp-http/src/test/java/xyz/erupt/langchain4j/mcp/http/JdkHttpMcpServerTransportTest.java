package xyz.erupt.langchain4j.mcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkHttpMcpServerTransportTest {

    private JdkHttpMcpServerTransport transport;

    @BeforeEach
    void start() {
        transport = JdkHttpMcpServerTransport.builder()
                .handler(McpHttpHandler.of("sample", "1.0", List.of(new SampleServer())))
                .port(0)
                .maxRequestBodySize(1024)
                .build();
    }

    @AfterEach
    void stop() {
        transport.close();
    }

    @Test
    void should_serve_langchain4j_client() throws Exception {
        try (McpClient client = DefaultMcpClient.builder()
                .transport(StreamableHttpMcpTransport.builder()
                        .url(transport.endpoint().toString())
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .build()) {
            assertThat(client.listTools()).extracting(ToolSpecification::name).containsExactly("add");
            assertThat(client.executeTool(ToolExecutionRequest.builder()
                                    .name("add")
                                    .arguments("{\"a\":2,\"b\":3}")
                                    .build())
                            .resultText())
                    .isEqualTo("5");
            assertThat(client.listPrompts()).hasSize(1);
            assertThat(client.getPrompt("greet", Map.of("name", "Ann")).messages()).hasSize(1);
            assertThat(client.listResources()).hasSize(1);
            assertThat(client.readResource("docs://guide").contents()).hasSize(1);
            client.checkHealth();
        }
    }

    @Test
    void should_answer_404_and_413() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> notFound = http.send(
                json(transport.endpoint().resolve("/other"), "{}"), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> tooLarge = http.send(
                json(transport.endpoint(), "\"" + "x".repeat(2048) + "\""), HttpResponse.BodyHandlers.ofString());

        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(transport.endpoint().getHost()).isEqualTo("127.0.0.1");
    }

    private static HttpRequest json(java.net.URI uri, String body) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
