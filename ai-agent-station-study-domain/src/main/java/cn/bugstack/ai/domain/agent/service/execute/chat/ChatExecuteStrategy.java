package cn.bugstack.ai.domain.agent.service.execute.chat;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessage;
import cn.bugstack.ai.domain.agent.service.memory.MemoryFoldingPipeline;
import cn.bugstack.ai.domain.agent.service.model.ModelSelectionService;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatExecuteStrategy implements IExecuteStrategy {

    public static final String TYPE = "chat";

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAgentRepository repository;

    @Resource
    private ChatMessageRecorder messageRecorder;

    @Override
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        String sessionId = requestParameter.getSessionId();
        String agentId = requestParameter.getAiAgentId();
        AiAgentVO agent = repository.queryAgentById(agentId);
        if (agent == null) {
            send(emitter, AutoAgentExecuteResultEntity.createErrorResult("Agent 不存在：" + agentId, sessionId));
            return;
        }

        List<HistoryMessage> history = messageRecorder.getHistory(sessionId);
        messageRecorder.recordUser(sessionId, agentId, 0, requestParameter.getMessage());

        String selectedModelId = ModelSelectionService.select(requestParameter.getModelId(), agent.getModelId());
        OpenAiChatModel chatModel = applicationContext.getBean(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(selectedModelId), OpenAiChatModel.class);
        String systemPrompt = buildChatSystemPrompt(agent, requestParameter.getRouteType());

        List<Message> messages = buildMessages(history, requestParameter.getMessage());
        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem(systemPrompt).build();

        long start = System.currentTimeMillis();
        String reply;
        String status = "success";
        String errorMessage = "";
        try {
            reply = chatClient.prompt().messages(messages).call().content();
            if (reply == null || reply.isBlank()) {
                reply = "我在，这条消息我收到了。你可以继续问我，或者让我调用 Agent 去处理具体任务。";
            }
        } catch (Exception e) {
            status = "failed";
            errorMessage = e.getMessage();
            log.error("Chat 协调回复失败: {}", e.getMessage(), e);
            reply = ReActExecuteStrategy.isServiceUnavailableError(e.getMessage())
                    ? "模型服务暂时繁忙（HTTP 503），本次未重复发送请求。请切换到 2001（DeepSeek Chat）或 2002（SenseNova），也可以稍后重试。"
                    : "我这边暂时没有拿到模型回复，但消息已经收到。你可以稍后重试，或者直接描述要执行的任务。";
        }
        int durationMs = (int) Math.max(0, System.currentTimeMillis() - start);

        messageRecorder.recordAssistant(sessionId, agentId, 0, 0, reply, null);
        recordLlmLog(sessionId, agentId, selectedModelId, systemPrompt, requestParameter.getMessage(), reply,
                history.size(), messages.size(), durationMs, status, errorMessage);
        send(emitter, AutoAgentExecuteResultEntity.builder()
                .type(TYPE)
                .step(null)
                .content(reply)
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build());
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private String buildChatSystemPrompt(AiAgentVO agent, String routeType) {
        String soul = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt().trim();
        String feedbackInstruction = "feedback".equals(routeType) ? """

                当前消息已被系统识别为用户/业务反馈，并已自动进入 Feedback 评测队列。
                回复时请先明确告知“已记录反馈”，再简要复述问题点；不要主动声称已经排查项目、读取代码或运行命令。
                """ : "";
        return """
                你是 AI 工作台里的 Chat 协调器。
                你的职责是先自然回答用户的普通问题；只有当用户明确需要工具、计划、执行、验证时，才提示可以交给 Agent 执行。
                不要编造工具调用结果，不要展示执行进度，不要声称已经完成没有实际执行的任务。
                请使用简洁、自然的中文回复。
                """ + feedbackInstruction + (soul.isBlank() ? "" : "\n当前 Agent 灵魂设定：\n" + soul);
    }

    private List<Message> buildMessages(List<HistoryMessage> history, String currentMessage) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (history != null) {
            int start = Math.max(0, history.size() - 12);
            for (int i = start; i < history.size(); i++) {
                HistoryMessage h = history.get(i);
                maps.add(Map.of("role", h.getRole(), "content", h.getContent() == null ? "" : h.getContent()));
            }
        }
        maps.add(Map.of("role", "user", "content", currentMessage == null ? "" : currentMessage));
        maps = MemoryFoldingPipeline.fold(maps);

        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> item : maps) {
            String role = String.valueOf(item.get("role"));
            String content = String.valueOf(item.getOrDefault("content", ""));
            if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            } else {
                messages.add(new UserMessage(content));
            }
        }
        return messages;
    }

    private void recordLlmLog(String sessionId, String agentId, String modelId, String systemPrompt, String userMessage,
                              String reply, int historySize, int foldedSize, int durationMs, String status, String errorMessage) {
        try {
            messageRecorder.recordLlmLog(ChatMessageRecorder.LlmLogEntry.builder()
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .modelName(modelId)
                    .mode(TYPE)
                    .durationMs(durationMs)
                    .status(status)
                    .errorMessage(errorMessage)
                    .historyMsgCount(historySize)
                    .foldedMsgCount(foldedSize)
                    .systemPromptLen(systemPrompt == null ? 0 : systemPrompt.length())
                    .userMessageLen(userMessage == null ? 0 : userMessage.length())
                    .assistantResponseLen(reply == null ? 0 : reply.length())
                    .build());
        } catch (Exception e) {
            log.warn("记录 Chat LLM 日志失败: {}", e.getMessage());
        }
    }

    private void send(ResponseBodyEmitter emitter, AutoAgentExecuteResultEntity result) throws Exception {
        emitter.send("data: " + JSON.toJSONString(result) + "\n\n");
    }
}
