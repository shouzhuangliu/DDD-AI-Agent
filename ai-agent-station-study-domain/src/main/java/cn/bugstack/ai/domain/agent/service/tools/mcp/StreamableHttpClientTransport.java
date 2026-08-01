package cn.bugstack.ai.domain.agent.service.tools.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Minimal MCP Streamable HTTP transport for the MCP SDK version used by this project. */
public final class StreamableHttpClientTransport implements McpClientTransport {

    private final URI endpoint;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, String> headers;
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> messageHandler;
    private volatile String sessionId;

    public StreamableHttpClientTransport(String endpoint,
                                         ObjectMapper objectMapper,
                                         HttpClient httpClient,
                                         Map<String, String> headers) {
        this.endpoint = URI.create(Objects.requireNonNull(endpoint, "endpoint"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.messageHandler = Objects.requireNonNull(handler, "handler");
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> sendBlocking(message));
    }

    private void sendBlocking(McpSchema.JSONRPCMessage message) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(message)));
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            headers.forEach(builder::header);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("MCP HTTP " + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.toLowerCase().contains("text/event-stream")) {
                dispatchSse(response.body());
            } else {
                dispatch(response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("MCP Streamable HTTP request failed", e);
        }
    }

    private void dispatchSse(String body) throws IOException {
        for (String event : body.split("\\n\\s*\\n")) {
            for (String line : event.split("\\r?\\n")) {
                if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).trim();
                    if (!data.isBlank()) {
                        dispatch(data);
                    }
                }
            }
        }
    }

    private void dispatch(String json) throws IOException {
        if (messageHandler != null) {
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, json);
            messageHandler.apply(Mono.just(message)).block();
        }
    }

    @Override
    public Mono<Void> closeGracefully() {
        sessionId = null;
        messageHandler = null;
        return Mono.empty();
    }

    @Override
    public <T> T unmarshalFrom(Object value, TypeReference<T> type) {
        return objectMapper.convertValue(value, type);
    }
}
