# langchain4j-mcp-http

Serve [LangChain4j](https://github.com/langchain4j/langchain4j) `@Tool` methods as an MCP server over **Streamable HTTP**, plus **prompts** and **resources**, with a Spring Boot starter.

[`langchain4j-community-mcp-server`](https://github.com/langchain4j/langchain4j-community/tree/main/mcp/langchain4j-community-mcp-server) turns `@Tool` methods into an MCP server, but it only speaks stdio and only supports tools. This project adds what remote MCP clients need:

| | community MCP server | + langchain4j-mcp-http |
|---|---|---|
| Transport | stdio | stateless Streamable HTTP (JDK HttpServer, Spring MVC, or any server) |
| Tools | ✅ | ✅ (served by the community `McpServer`) |
| Prompts / Resources | ❌ | ✅ `@McpPrompt` / `@McpResource` |
| `ping`, string JSON-RPC ids | ❌ | ✅ |
| Spring Boot | stdio only | `@McpService` beans exposed at `/mcp` |

> The HTTP transport has also been proposed upstream in [langchain4j-community#896](https://github.com/langchain4j/langchain4j-community/pull/896).
> If it is merged, this project will keep only what upstream does not provide.

## Spring Boot

```xml
<dependency>
    <groupId>xyz.erupt</groupId>
    <artifactId>langchain4j-mcp-http-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
@McpService
public class OrderTools {

    @Tool("Find an order by id")
    public Order findOrder(@P("order id") String id) { ... }

    @McpPrompt(description = "Investigate a failed order")
    public String investigate(@McpPromptArg(description = "order id") String id) {
        return "Look up order " + id + " and explain why it failed.";
    }

    @McpResource(uri = "orders://statuses", mimeType = "text/markdown")
    public String statuses() { return "# Order statuses ..."; }
}
```

Only `@McpService` beans are exposed, so `@Tool` beans written for your own AI services stay private.
The endpoint is a Spring MVC route, which means servlet filters and Spring Security apply to it. Tools run on the request thread, so `RequestContextHolder` and request headers are available inside them.

```yaml
langchain4j:
  mcp:
    http:
      path: /mcp                 # default
      server-name: orders        # default: spring.application.name
      server-version: 1.0.0
      allowed-origins: []        # browser origins allowed to call the endpoint
```

## Plain Java

```xml
<dependency>
    <groupId>xyz.erupt</groupId>
    <artifactId>langchain4j-mcp-http</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
McpHttpHandler handler = McpHttpHandler.of("orders", "1.0.0", List.of(new OrderTools()));
try (JdkHttpMcpServerTransport transport = JdkHttpMcpServerTransport.builder()
        .handler(handler)
        .port(8080)            // binds 127.0.0.1 by default; .host("0.0.0.0") for remote clients
        .build()) {
    transport.awaitClose();
}
```

`McpHttpHandler.handle(method, headerLookup, body)` does not depend on any HTTP server, so it can be mounted on Servlet, Vert.x, Netty, and so on.

## Client configuration

```json
{
  "mcpServers": {
    "orders": { "type": "http", "url": "http://localhost:8080/mcp" }
  }
}
```

## Protocol notes

The transport is the stateless form of [Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#streamable-http):

- `POST` with one JSON-RPC message. A request gets `200 application/json`; a notification or response gets `202`.
- No sessions and no SSE: `GET` and `DELETE` get `405`.
- The `Origin` header is validated (DNS-rebinding protection): requests that carry an `Origin` are rejected with `403` unless it is listed in `allowed-origins`.
- Other status codes: `415` for a non-JSON `Content-Type`, `406` when `Accept` excludes JSON, `400` for malformed JSON or batches, and `413` for oversized bodies (JDK transport, 4 MiB by default).

Not supported (yet): SSE streaming, server-initiated requests (sampling, elicitation), sessions, and resource subscriptions.

## Build

Requires Java 17+.

```bash
mvn verify
```

## License

Apache License 2.0
