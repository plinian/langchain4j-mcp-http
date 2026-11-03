package xyz.erupt.langchain4j.mcp.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link McpPrompt prompts} and {@link McpResource resources} declared by a set of objects, served through
 * {@code prompts/list}, {@code prompts/get}, {@code resources/list} and {@code resources/read}.
 */
public final class McpCatalog {

    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int RESOURCE_NOT_FOUND = -32002;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, Entry<McpPrompt>> prompts = new LinkedHashMap<>();
    private final Map<String, Entry<McpResource>> resources = new LinkedHashMap<>();

    public McpCatalog(Collection<?> objects) {
        for (Object object : objects) {
            for (Method method : object.getClass().getMethods()) {
                McpPrompt prompt = method.getAnnotation(McpPrompt.class);
                if (prompt != null) {
                    register(prompts, nameOf(prompt.name(), method), new Entry<>(prompt, object, method), "prompt");
                }
                McpResource resource = method.getAnnotation(McpResource.class);
                if (resource != null) {
                    if (method.getParameterCount() != 0) {
                        throw new IllegalArgumentException("@McpResource method must have no parameters: " + method);
                    }
                    register(resources, resource.uri(), new Entry<>(resource, object, method), "resource");
                }
            }
        }
    }

    public boolean hasPrompts() {
        return !prompts.isEmpty();
    }

    public boolean hasResources() {
        return !resources.isEmpty();
    }

    /**
     * @return whether {@link #handle(String, JsonNode)} serves the given JSON-RPC method
     */
    public boolean supports(String method) {
        return switch (method) {
            case "prompts/list", "prompts/get", "resources/list", "resources/read", "resources/templates/list" -> true;
            default -> false;
        };
    }

    /**
     * @return the JSON-RPC result of a method for which {@link #supports(String)} is {@code true}
     * @throws McpError when the request cannot be served
     */
    public JsonNode handle(String method, JsonNode params) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        switch (method) {
            case "prompts/list" -> {
                ArrayNode list = result.putArray("prompts");
                prompts.forEach((name, entry) -> describePrompt(list.addObject(), name, entry));
            }
            case "prompts/get" -> getPrompt(result, params);
            case "resources/list" -> {
                ArrayNode list = result.putArray("resources");
                resources.forEach((uri, entry) -> describeResource(list.addObject(), uri, entry));
            }
            case "resources/read" -> readResource(result, params);
            case "resources/templates/list" -> result.putArray("resourceTemplates");
            default -> throw new McpError(INVALID_PARAMS, "Unsupported method: " + method);
        }
        return result;
    }

    private static void describePrompt(ObjectNode target, String name, Entry<McpPrompt> entry) {
        target.put("name", name);
        putIfNotBlank(target, "description", entry.annotation.description());
        ArrayNode arguments = target.putArray("arguments");
        for (Parameter parameter : entry.method.getParameters()) {
            McpPromptArg arg = argOf(parameter, entry.method);
            ObjectNode argument = arguments.addObject().put("name", nameOf(arg.name(), parameter));
            putIfNotBlank(argument, "description", arg.description());
            argument.put("required", arg.required());
        }
    }

    private void getPrompt(ObjectNode result, JsonNode params) {
        String name = params.path("name").asText(null);
        Entry<McpPrompt> entry = name == null ? null : prompts.get(name);
        if (entry == null) {
            throw new McpError(INVALID_PARAMS, "Unknown prompt: " + name);
        }
        JsonNode values = params.path("arguments");
        List<Object> args = new ArrayList<>();
        for (Parameter parameter : entry.method.getParameters()) {
            McpPromptArg arg = argOf(parameter, entry.method);
            String argName = nameOf(arg.name(), parameter);
            JsonNode value = values.get(argName);
            if ((value == null || value.isNull()) && arg.required()) {
                throw new McpError(INVALID_PARAMS, "Missing required argument: " + argName);
            }
            args.add(value == null || value.isNull() ? null : value.asText());
        }
        putIfNotBlank(result, "description", entry.annotation.description());
        result.putArray("messages")
                .addObject()
                .put("role", "user")
                .putObject("content")
                .put("type", "text")
                .put("text", String.valueOf(invoke(entry, args.toArray())));
    }

    private static void describeResource(ObjectNode target, String uri, Entry<McpResource> entry) {
        target.put("uri", uri).put("name", nameOf(entry.annotation.name(), entry.method));
        putIfNotBlank(target, "description", entry.annotation.description());
        target.put("mimeType", entry.annotation.mimeType());
    }

    private void readResource(ObjectNode result, JsonNode params) {
        String uri = params.path("uri").asText(null);
        Entry<McpResource> entry = uri == null ? null : resources.get(uri);
        if (entry == null) {
            throw new McpError(RESOURCE_NOT_FOUND, "Resource not found: " + uri);
        }
        result.putArray("contents")
                .addObject()
                .put("uri", uri)
                .put("mimeType", entry.annotation.mimeType())
                .put("text", String.valueOf(invoke(entry, new Object[0])));
    }

    private static Object invoke(Entry<?> entry, Object[] args) {
        try {
            return entry.method.invoke(entry.target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new McpError(INTERNAL_ERROR, cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName());
        } catch (IllegalAccessException e) {
            throw new McpError(INTERNAL_ERROR, e.getMessage());
        }
    }

    private static McpPromptArg argOf(Parameter parameter, Method method) {
        McpPromptArg arg = parameter.getAnnotation(McpPromptArg.class);
        if (arg == null || parameter.getType() != String.class) {
            throw new IllegalArgumentException("@McpPrompt parameters must be @McpPromptArg String: " + method);
        }
        return arg;
    }

    private static <T> void register(Map<String, Entry<T>> map, String key, Entry<T> entry, String kind) {
        // public methods of non-public classes (e.g. package-private or nested beans) are not invocable otherwise
        entry.method.trySetAccessible();
        if (map.putIfAbsent(key, entry) != null) {
            throw new IllegalArgumentException("Duplicated " + kind + ": " + key);
        }
    }

    private static String nameOf(String declared, Method method) {
        return declared.isBlank() ? method.getName() : declared;
    }

    private static String nameOf(String declared, Parameter parameter) {
        return declared.isBlank() ? parameter.getName() : declared;
    }

    private static void putIfNotBlank(ObjectNode target, String field, String value) {
        if (!value.isBlank()) {
            target.put(field, value);
        }
    }

    private record Entry<A>(A annotation, Object target, Method method) {}

    /**
     * A JSON-RPC error to return to the client.
     */
    public static final class McpError extends RuntimeException {

        private final int code;

        public McpError(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
