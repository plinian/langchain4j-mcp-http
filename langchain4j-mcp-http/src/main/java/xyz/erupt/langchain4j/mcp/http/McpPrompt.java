package xyz.erupt.langchain4j.mcp.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a public method as an MCP prompt ({@code prompts/list}, {@code prompts/get}). The method returns the
 * prompt text, which is sent as a single user message; its parameters must be {@link McpPromptArg} strings.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpPrompt {

    /** Prompt name; defaults to the method name. */
    String name() default "";

    String description() default "";
}
