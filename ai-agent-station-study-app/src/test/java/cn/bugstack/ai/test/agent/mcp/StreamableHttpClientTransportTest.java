package cn.bugstack.ai.test.agent.mcp;

import cn.bugstack.ai.domain.agent.service.tools.mcp.StreamableHttpClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamableHttpClientTransportTest {

    @Test
    void sendsJsonRpcMessagesToStreamableHttpEndpoint() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();

        try {
            StreamableHttpClientTransport transport = new StreamableHttpClientTransport(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    new ObjectMapper(), java.net.http.HttpClient.newHttpClient(), java.util.Map.of());
            transport.connect(message -> Mono.empty()).block();
            transport.sendMessage(new McpSchema.JSONRPCNotification(
                    McpSchema.JSONRPC_VERSION, "notifications/initialized", java.util.Map.of())).block();

            assertTrue(requestBody.get().contains("notifications/initialized"));
        } finally {
            server.stop(0);
        }
    }
}
