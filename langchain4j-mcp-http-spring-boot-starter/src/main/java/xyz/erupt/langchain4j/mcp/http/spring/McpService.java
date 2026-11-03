package xyz.erupt.langchain4j.mcp.http.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a bean whose {@code @Tool}, {@code @McpPrompt} and {@code @McpResource} methods are exposed over MCP.
 * Only beans carrying this annotation are exposed, so {@code @Tool} beans meant for AI services stay private.
 */
@Documented
@Component
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface McpService {}
