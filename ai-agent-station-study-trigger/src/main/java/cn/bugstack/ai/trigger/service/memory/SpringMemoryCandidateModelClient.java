package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Uses the configured model only to turn new conversation evidence into a controlled memory action. */
@Component
public class SpringMemoryCandidateModelClient implements MemoryCandidateModelClient {
    private static final String PROMPT = """
            你是 Agent 长期业务记忆提取器，只返回一个纯 JSON 对象，不要 Markdown。
            只提取稳定、可复用且有当前会话原文证据的业务知识。实时数据、今日数量、寒暄、单字回复不进入长期记忆。
            用户偏好、未审核 Case 判断、工具/MCP/模型异常不进入长期记忆。
            memoryType 仅允许 BUSINESS_RULE、OPERATING_PLAYBOOK、CAPABILITY_BOUNDARY。
            operation 只能是 CREATE、UPDATE、RETIRE、NOOP；没有稳定知识时返回 NOOP；更新或失效已有记忆时必须给出 targetMemoryId。
            输出格式：{"operation":"NOOP","targetMemoryId":"","eligible":false,"memoryType":"","memoryKey":"","title":"","summary":"","content":{},"confidence":0,"evidence":[{"messageId":1,"quote":"原文连续片段"}]}。
            不得编造 messageId 或 quote。
            """;

    private final ApplicationContext applicationContext;

    public SpringMemoryCandidateModelClient(ApplicationContext applicationContext) { this.applicationContext = applicationContext; }

    @Override
    public Extraction extract(ExtractionRequest request) {
        OpenAiChatModel model = applicationContext.getBean(
                AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(request.modelId()), OpenAiChatModel.class);
        String raw = ChatClient.builder(model).defaultSystem(PROMPT).build().prompt().user(request.context()).call().content();
        if (raw == null || raw.isBlank() || raw.contains("```")) {
            throw new IllegalArgumentException("long-term memory extraction must be plain JSON");
        }
        return parseExtraction(raw);
    }

    static Extraction parseExtraction(String raw) {
        JSONObject root = JSON.parseObject(raw);
        if (root == null) throw new IllegalArgumentException("long-term memory extraction JSON is empty");
        List<Evidence> evidence = new ArrayList<>();
        JSONArray values = root.getJSONArray("evidence");
        if (values != null) for (Object value : values) {
            if (value instanceof JSONObject item) {
                evidence.add(new Evidence(item.getLong("messageId"), bounded(item.getString("quote"), 1000)));
            }
        }
        Object content = root.get("content");
        return new Extraction(operation(root.getString("operation")), bounded(root.getString("targetMemoryId"), 64),
                Boolean.TRUE.equals(root.getBoolean("eligible")), bounded(root.getString("memoryType"), 32),
                bounded(root.getString("memoryKey"), 191), bounded(root.getString("title"), 255),
                bounded(root.getString("summary"), 2000), content == null ? "{}" : JSON.toJSONString(content),
                Math.max(0, Math.min(100, root.getIntValue("confidence"))), List.copyOf(evidence));
    }

    private static String bounded(String value, int max) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String operation(String value) {
        String normalized = value == null ? "NOOP" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CREATE", "UPDATE", "RETIRE", "NOOP" -> normalized;
            default -> "NOOP";
        };
    }
}
