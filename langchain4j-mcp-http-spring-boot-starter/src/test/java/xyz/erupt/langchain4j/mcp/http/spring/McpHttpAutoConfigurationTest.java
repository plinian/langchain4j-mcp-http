package xyz.erupt.langchain4j.mcp.http.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import xyz.erupt.langchain4j.mcp.http.McpPrompt;
import xyz.erupt.langchain4j.mcp.http.McpPromptArg;
import xyz.erupt.langchain4j.mcp.http.McpResource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=demo", "langchain4j.mcp.http.path=/api/mcp"})
class McpHttpAutoConfigurationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void should_expose_mcp_services_only() throws Exception {
        try (McpClient client = DefaultMcpClient.builder()
                .transport(StreamableHttpMcpTransport.builder()
                        .url("http://localhost:" + port + "/api/mcp")
                        .customHeaders(java.util.Map.of("X-User", "ann"))
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .build()) {
            assertThat(client.listTools()).extracting(ToolSpecification::name).containsExactly("whoami");
            String result = client.executeTool(ToolExecutionRequest.builder()
                            .name("whoami")
                            .arguments("{\"greeting\":\"hi\"}")
                            .build())
                    .resultText();
            assertThat(result).isEqualTo("hi ann"); // the tool runs on the request thread and sees its headers
            assertThat(client.listPrompts()).hasSize(1);
            assertThat(client.readResource("demo://readme").contents()).hasSize(1);
        }
    }

    @Test
    void should_answer_405_for_get() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    // classes nested in a test class are excluded from component scanning, so import them explicitly
    @SpringBootApplication
    @Import({DemoService.class, PrivateTools.class})
    static class DemoApplication {}

    @McpService
    static class DemoService {

        @Tool
        public String whoami(@P("greeting") String greeting) {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return greeting + " " + attributes.getRequest().getHeader("X-User");
        }

        @McpPrompt(description = "Summarize")
        public String summarize(@McpPromptArg String text) {
            return "Summarize: " + text;
        }

        @McpResource(uri = "demo://readme")
        public String readme() {
            return "readme";
        }
    }

    @Component
    static class PrivateTools {

        @Tool
        public String secret() {
            return "not exposed";
        }
    }
}
