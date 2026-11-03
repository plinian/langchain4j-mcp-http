package xyz.erupt.langchain4j.mcp.http;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

class SampleServer { // package-private on purpose: prompts and resources must still be invocable

    @Tool("Adds two numbers")
    public long add(@P("first") long a, @P("second") long b) {
        return a + b;
    }

    @McpPrompt(name = "greet", description = "Greets someone")
    public String greet(
            @McpPromptArg(description = "who") String name,
            @McpPromptArg(name = "lang", required = false) String language) {
        return ("zh".equals(language) ? "你好, " : "Hello, ") + name;
    }

    @McpResource(uri = "docs://guide", description = "The guide", mimeType = "text/markdown")
    public String guide() {
        return "# Guide";
    }
}
