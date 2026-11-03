package xyz.erupt.langchain4j.mcp.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code String} argument of an {@link McpPrompt} method. Optional arguments that the client omits are
 * passed as {@code null}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface McpPromptArg {

    /** Argument name; defaults to the parameter name (requires {@code -parameters}). */
    String name() default "";

    String description() default "";

    boolean required() default true;
}
