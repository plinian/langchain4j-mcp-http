package xyz.erupt.langchain4j.mcp.http.spring;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import xyz.erupt.langchain4j.mcp.http.McpCatalog;
import xyz.erupt.langchain4j.mcp.http.McpHttpHandler;

/**
 * Exposes every {@link McpService} bean at {@code langchain4j.mcp.http.path} (default {@code /mcp}) over the
 * stateless Streamable HTTP transport, through Spring MVC so that servlet filters and Spring Security apply.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({McpServer.class, RouterFunction.class})
@ConditionalOnProperty(prefix = McpHttpProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(McpHttpProperties.class)
public class McpHttpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public McpHttpHandler mcpHttpHandler(McpHttpProperties properties, ApplicationContext context) {
        List<Object> services = new ArrayList<>();
        for (Map.Entry<String, Object> bean : context.getBeansWithAnnotation(McpService.class).entrySet()) {
            if (AopUtils.isAopProxy(bean.getValue())) {
                // McpServer reads @Tool from the runtime class; unwrapping the proxy would bypass its advice
                throw new IllegalStateException("@McpService bean must not be proxied: " + bean.getKey()
                        + ". Expose an unproxied facade that delegates to the proxied service.");
            }
            services.add(bean.getValue());
        }
        McpImplementation serverInfo = new McpImplementation();
        serverInfo.setName(properties.getServerName() != null
                ? properties.getServerName()
                : context.getEnvironment().getProperty("spring.application.name", "mcp-server"));
        serverInfo.setVersion(properties.getServerVersion());
        return new McpHttpHandler(
                new McpServer(services, serverInfo), new McpCatalog(services), properties.getAllowedOrigins());
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(McpHttpProperties properties, McpHttpHandler handler) {
        return RouterFunctions.route()
                .route(request -> request.path().equals(properties.getPath()), request -> {
                    String body = request.method().matches("POST") ? request.body(String.class) : null;
                    McpHttpHandler.Response response = handler.handle(
                            request.method().name(), request.headers()::firstHeader, body);
                    ServerResponse.BodyBuilder builder = ServerResponse.status(response.statusCode());
                    response.headers().forEach(builder::header);
                    return response.body() == null ? builder.build() : builder.body(response.body());
                })
                .build();
    }
}
