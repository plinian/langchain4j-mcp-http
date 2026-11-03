package xyz.erupt.langchain4j.mcp.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves an {@link McpHttpHandler} with the HTTP server built into the JDK ({@code com.sun.net.httpserver}).
 *
 * <pre>{@code
 * McpHttpHandler handler = McpHttpHandler.of("my-server", "1.0.0", List.of(new MyTools()));
 * try (JdkHttpMcpServerTransport transport = JdkHttpMcpServerTransport.builder().handler(handler).port(8080).build()) {
 *     transport.awaitClose();
 * }
 * }</pre>
 *
 * Binds to {@code 127.0.0.1} by default, as the specification recommends for local servers; set
 * {@link Builder#host(String)} to {@code 0.0.0.0} to accept remote connections.
 */
public class JdkHttpMcpServerTransport implements Closeable {

    private final McpHttpHandler handler;
    private final String path;
    private final int maxRequestBodySize;
    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final Object closeLock = new Object();
    private boolean closed;

    private JdkHttpMcpServerTransport(Builder builder) {
        this.handler = Objects.requireNonNull(builder.handler, "handler");
        this.path = builder.path;
        this.maxRequestBodySize = builder.maxRequestBodySize;
        this.executor = newExecutor(builder.maxThreads);
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(builder.host, builder.port), 0);
        } catch (IOException e) {
            executor.shutdownNow();
            throw new UncheckedIOException(e);
        }
        httpServer.createContext(path, this::exchange);
        httpServer.setExecutor(executor);
        httpServer.start();
    }

    /** @return the port the server listens on; useful when it was started on port {@code 0} */
    public int port() {
        return httpServer.getAddress().getPort();
    }

    /** @return the URL of the MCP endpoint, e.g. {@code http://127.0.0.1:8080/mcp} */
    public URI endpoint() {
        InetSocketAddress address = httpServer.getAddress();
        return URI.create("http://" + address.getHostString() + ":" + address.getPort() + path);
    }

    /** Blocks until this transport is closed. */
    public void awaitClose() throws InterruptedException {
        synchronized (closeLock) {
            while (!closed) {
                closeLock.wait();
            }
        }
    }

    @Override
    public void close() {
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closed = true;
            closeLock.notifyAll();
        }
        httpServer.stop(0);
        executor.shutdownNow();
    }

    private void exchange(HttpExchange exchange) throws IOException {
        // HttpExchange is AutoCloseable only since Java 18
        try {
            McpHttpHandler.Response response = respond(exchange);
            response.headers().forEach(exchange.getResponseHeaders()::set);
            if (response.body() == null) {
                exchange.sendResponseHeaders(response.statusCode(), -1);
                return;
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.statusCode(), bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private McpHttpHandler.Response respond(HttpExchange exchange) throws IOException {
        if (!path.equals(exchange.getRequestURI().getPath())) {
            return McpHttpHandler.Response.empty(404);
        }
        InputStream input = exchange.getRequestBody();
        byte[] body = input.readNBytes(maxRequestBodySize + 1);
        if (body.length > maxRequestBodySize) {
            return McpHttpHandler.Response.empty(413);
        }
        try {
            return handler.handle(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders()::getFirst,
                    new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return McpHttpHandler.Response.empty(500);
        }
    }

    private static ExecutorService newExecutor(int maxThreads) {
        AtomicInteger counter = new AtomicInteger();
        // tools may block, so threads are created on demand up to maxThreads; beyond that the
        // accepting thread runs the request itself, which slows down accepting new connections
        return new ThreadPoolExecutor(0, maxThreads, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "mcp-http-server-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private McpHttpHandler handler;
        private String host = "127.0.0.1";
        private int port = 8080;
        private String path = "/mcp";
        private int maxThreads = 64;
        private int maxRequestBodySize = 4 * 1024 * 1024;

        /** The handler to serve. Required. */
        public Builder handler(McpHttpHandler handler) {
            this.handler = handler;
            return this;
        }

        /** The address to bind to. Default: {@code 127.0.0.1}. */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        /** The port to listen on; {@code 0} picks a free port. Default: {@code 8080}. */
        public Builder port(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            this.port = port;
            return this;
        }

        /** The path of the MCP endpoint. Default: {@code /mcp}. */
        public Builder path(String path) {
            if (path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException("path must start with /");
            }
            this.path = path;
            return this;
        }

        /** The maximum number of requests handled concurrently. Default: {@code 64}. */
        public Builder maxThreads(int maxThreads) {
            this.maxThreads = positive(maxThreads, "maxThreads");
            return this;
        }

        /** The maximum request body size in bytes, larger requests get {@code 413}. Default: 4 MiB. */
        public Builder maxRequestBodySize(int maxRequestBodySize) {
            this.maxRequestBodySize = positive(maxRequestBodySize, "maxRequestBodySize");
            return this;
        }

        /** Starts the HTTP server. */
        public JdkHttpMcpServerTransport build() {
            return new JdkHttpMcpServerTransport(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
