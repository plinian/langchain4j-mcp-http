package xyz.erupt.langchain4j.mcp.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a public no-argument method returning the content as text as an MCP resource
 * ({@code resources/list}, {@code resources/read}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpResource {

    /** Resource URI, e.g. {@code docs://guide}. */
    String uri();

    /** Resource name; defaults to the method name. */
    String name() default "";

    String description() default "";

    String mimeType() default "text/plain";
}
