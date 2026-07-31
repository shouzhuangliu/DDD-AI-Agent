package cn.bugstack.ai.trigger.service.capability;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityRegistryServiceTest {

    @Test
    void shouldTreatCommandArgsEndpointAsStdioEvenWhenStoredTransportIsWrong() {
        JSONObject endpoint = JSON.parseObject("""
                {
                  "command": "python",
                  "args": ["mcp-test-server/inventory_feedback_mcp.py"]
                }
                """);

        String actual = CapabilityRegistryService.normalizeTransportForConnectivity("sse", endpoint);

        assertEquals("stdio", actual);
    }
}
