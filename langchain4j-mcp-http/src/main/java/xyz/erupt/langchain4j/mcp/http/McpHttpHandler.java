package xyz.erupt.langchain4j.mcp.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Server-agnostic handler for the stateless form of the
 * <a href="https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#streamable-http">Streamable HTTP</a>
 * transport: every {@code POST} carries one JSON-RPC message and, if it is a request, receives a single
 * {@code application/json} response. No session and no SSE stream, so {@code GET}/{@code DELETE} get {@code 405}.
 *
 * <p>Tools ({@code @Tool}) are served by LangChain4j's {@link McpServer}; prompts ({@link McpPrompt}), resources
 * ({@link McpResource}) and {@code ping} are served by this handler, since {@link McpServer} does not support them.
 *
 * <p>Mount it on any HTTP server, or use {@link JdkHttpMcpServerTransport} or the Spring Boot starter.
 */
public class McpHttpHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String APPLICATION_JSON = "application/json";
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;

    private final McpServer server;
    private final McpCatalog catalog;
    private final Set<String> allowedOrigins;

    /**
     * @param server         serves {@code initialize}, {@code tools/list} and {@code tools/call}
     * @param catalog        serves prompts and resources
     * @param allowedOrigins origins whose browser requests are accepted; requests with any other {@code Origin}
     *                       header are answered with {@code 403}, as the specification requires to prevent DNS
     *                       rebinding. Non-browser MCP clients do not send {@code Origin}.
     */
    public McpHttpHandler(McpServer server, McpCatalog catalog, Collection<String> allowedOrigins) {
        this.server = Objects.requireNonNull(server, "server");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    /**
     * Creates a handler exposing the {@code @Tool}, {@link McpPrompt} and {@link McpResource} methods of the given
     * objects, accepting no browser origin.
     */
    public static McpHttpHandler of(String name, String version, List<Object> objects) {
        McpImplementation serverInfo = new McpImplementation();
        serverInfo.setName(name);
        serverInfo.setVersion(version);
        return new McpHttpHandler(new McpServer(objects, serverInfo), new McpCatalog(objects), Set.of());
    }

    /**
     * Handles one HTTP request sent to the MCP endpoint.
     *
     * @param httpMethod the HTTP method, e.g. {@code POST}
     * @param headers    looks up a request header by name, case-insensitively; returns {@code null} when absent
     * @param body       the request body, may be {@code null}
     */
    public Response handle(String httpMethod, Function<String, String> headers, String body) {
        String origin = headers.apply("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            return Response.empty(403);
        }
        if (!"POST".equalsIgnoreCase(httpMethod)) {
            return new Response(405, Map.of("Allow", "POST"), null);
        }
        if (!isJson(headers.apply("Content-Type"))) {
            return Response.empty(415);
        }
        if (!acceptsJson(headers.apply("Accept"))) {
            return Response.empty(406);
        }

        JsonNode message;
        try {
            message = OBJECT_MAPPER.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            return error(400, null, PARSE_ERROR, "Parse error");
        }
        if (message == null || !message.isObject()) {
            return error(400, null, INVALID_REQUEST, "Invalid Request: expected a single JSON-RPC message");
        }

        JsonNode id = message.get("id");
        if (!message.has("method") || id == null || id.isNull()) {
            return Response.empty(202); // notifications and client responses need no reply
        }
        if (!id.isNumber() && !id.isTextual()) {
            return error(400, null, INVALID_REQUEST, "Invalid Request: id must be a string or a number");
        }

        String method = message.path("method").asText();
        if (method.equals("ping")) {
            return result(id, OBJECT_MAPPER.createObjectNode());
        }
        if (catalog.supports(method)) {
            try {
                return result(id, catalog.handle(method, message.path("params")));
            } catch (McpCatalog.McpError e) {
                return error(200, id, e.code(), e.getMessage());
            }
        }

        // McpServer works with numeric ids only. Each HTTP request gets its own response, so the original id
        // can be swapped for a placeholder and restored without any risk of mixing up requests.
        ObjectNode request = ((ObjectNode) message).deepCopy();
        request.put("id", 0L);
        McpJsonRpcMessage reply = server.handle(request);
        if (reply == null) {
            return Response.empty(202);
        }
        ObjectNode json = OBJECT_MAPPER.valueToTree(reply);
        json.set("id", id);
        if (method.equals("initialize") && json.at("/result/capabilities").isObject()) {
            ObjectNode capabilities = (ObjectNode) json.at("/result/capabilities");
            if (catalog.hasPrompts()) {
                capabilities.putObject("prompts");
            }
            if (catalog.hasResources()) {
                capabilities.putObject("resources");
            }
        }
        return json(200, json);
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith(APPLICATION_JSON);
    }

    private static boolean acceptsJson(String accept) {
        if (accept == null || accept.isBlank()) {
            return true;
        }
        for (String range : accept.split(",")) {
            String mediaType = range.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (mediaType.equals(APPLICATION_JSON) || mediaType.equals("application/*") || mediaType.equals("*/*")) {
                return true;
            }
        }
        return false;
    }

    private static Response result(JsonNode id, JsonNode result) {
        ObjectNode json = OBJECT_MAPPER.createObjectNode().put("jsonrpc", "2.0");
        json.set("id", id);
        json.set("result", result);
        return json(200, json);
    }

    private static Response error(int status, JsonNode id, int code, String message) {
        ObjectNode json = OBJECT_MAPPER.createObjectNode().put("jsonrpc", "2.0");
        json.set("id", id);
        json.putObject("error").put("code", code).put("message", message);
        return json(status, json);
    }

    private static Response json(int status, JsonNode json) {
        try {
            return new Response(status, Map.of("Content-Type", APPLICATION_JSON), OBJECT_MAPPER.writeValueAsString(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An HTTP response produced by {@link McpHttpHandler}.
     *
     * @param statusCode the HTTP status code
     * @param headers    response headers to set
     * @param body       the response body, or {@code null} when the response has no body
     */
    public record Response(int statusCode, Map<String, String> headers, String body) {

        static Response empty(int statusCode) {
            return new Response(statusCode, Map.of(), null);
        }
    }
}
