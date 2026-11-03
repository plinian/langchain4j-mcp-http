package xyz.erupt.langchain4j.mcp.http.spring;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(McpHttpProperties.PREFIX)
public class McpHttpProperties {

    public static final String PREFIX = "langchain4j.mcp.http";

    /** Whether to expose the MCP endpoint. */
    private boolean enabled = true;

    /** Path of the MCP endpoint. */
    private String path = "/mcp";

    /** Server name reported to clients; defaults to {@code spring.application.name}. */
    private String serverName;

    /** Server version reported to clients. */
    private String serverVersion = "1.0.0";

    /** Browser origins allowed to call the endpoint; requests from other origins get 403. */
    private List<String> allowedOrigins = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
