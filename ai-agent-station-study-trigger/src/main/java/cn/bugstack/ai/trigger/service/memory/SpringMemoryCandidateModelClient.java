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

@Component
public class SpringMemoryCandidateModelClient implements MemoryCandidateModelClient {

    private static final String PROMPT = """
            你是 Agent 长期记忆候选抽取器，只返回一个纯 JSON 对象，不要 Markdown。
            只提取稳定、可复用且有当前会话原文证据的业务知识。实时库存数、今日数量、寒暄、单字回复、
            未审核判断、工具/MCP/模型异常都不能成为长期记忆。
            允许类型仅为 BUSINESS_RULE、OPERATING_PLAYBOOK、CAPABILITY_BOUNDARY；会话不能生成 RESOLVED_CASE。
            输出：{"eligible":false,"memoryType":"","memoryKey":"","title":"","summary":"",
            "content":{},"confidence":0,"evidence":[{"messageId":1,"quote":"原文连续片段"}]}。
            没有稳定知识时 eligible=false；不得编造 messageId 或 quote。
            """;

    private final ApplicationContext applicationContext;

    public SpringMemoryCandidateModelClient(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Extraction extract(ExtractionRequest request) {
        OpenAiChatModel model = applicationContext.getBean(
                AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(request.modelId()), OpenAiChatModel.class);
        String raw = ChatClient.builder(model).defaultSystem(PROMPT).build().prompt().user(request.context()).call().content();
        if (raw == null || raw.isBlank() || raw.contains("```")) {
            throw new IllegalArgumentException("长期记忆候选必须是纯 JSON");
        }
        JSONObject root = JSON.parseObject(raw);
        if (root == null) throw new IllegalArgumentException("长期记忆候选 JSON 为空");
        List<Evidence> evidence = new ArrayList<>();
        JSONArray values = root.getJSONArray("evidence");
        if (values != null) for (Object value : values) {
            if (value instanceof JSONObject item) {
                evidence.add(new Evidence(item.getLong("messageId"), bounded(item.getString("quote"), 1000)));
            }
        }
        Object content = root.get("content");
        return new Extraction(Boolean.TRUE.equals(root.getBoolean("eligible")), bounded(root.getString("memoryType"), 32),
                bounded(root.getString("memoryKey"), 191), bounded(root.getString("title"), 255),
                bounded(root.getString("summary"), 2000), content == null ? "{}" : JSON.toJSONString(content),
                Math.max(0, Math.min(100, root.getIntValue("confidence"))), List.copyOf(evidence));
    }

    private static String bounded(String value, int max) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
