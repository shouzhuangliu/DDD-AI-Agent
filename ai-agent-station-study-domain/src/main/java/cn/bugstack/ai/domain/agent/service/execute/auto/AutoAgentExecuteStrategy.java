package cn.bugstack.ai.domain.agent.service.execute.auto;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.AgentExecutionState;
import cn.bugstack.ai.domain.agent.model.entity.AgentTodoItem;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentExecutionRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentModeEnum;
import cn.bugstack.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.memory.HistoryMessage;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动执行策略
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/5 09:49
 */
@Slf4j
@Service
public class AutoAgentExecuteStrategy implements IExecuteStrategy {
    @Resource
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;
    @Resource
    private ChatMessageRecorder messageRecorder;
    @Resource
    private IAgentExecutionRepository executionRepository;
    @Override
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        String sessionId = requestParameter.getSessionId();
        String agentId = requestParameter.getAiAgentId();
        String executionId = UUID.randomUUID().toString();
        int maxCycles = requestParameter.getMaxStep() == null || requestParameter.getMaxStep() <= 0
                ? 5 : Math.min(requestParameter.getMaxStep(), 5);
        executionRepository.create(AgentExecutionState.builder()
                .executionId(executionId).sessionId(sessionId).agentId(agentId)
                .modelId(requestParameter.getModelId()).routeType("plan").status("RUNNING")
                .maxCycles(maxCycles).maxSteps(30).stateJson("{\"todos\":[]}")
                .startedAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        var history = messageRecorder.getHistory(sessionId);
        messageRecorder.recordUser(sessionId, agentId, 0, requestParameter.getMessage());
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler();
        // 创建动态上下文并初始化必要字段
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(requestParameter.getMaxStep() != null ? requestParameter.getMaxStep() : 3);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(withConversationContext(history, requestParameter.getMessage()));
        dynamicContext.setValue("emitter", emitter);
        AgentTodoItem todo = AgentTodoItem.builder().todoId(UUID.randomUUID().toString())
                .content(requestParameter.getMessage()).status("IN_PROGRESS").owner("LEAD")
                .position(0).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        dynamicContext.setTodos(new java.util.ArrayList<>(List.of(todo)));
        sendTodoEvent(emitter, executionId, sessionId, dynamicContext.getTodos());

        String apply;
        try {
            apply = executeHandler.apply(requestParameter, dynamicContext);
        } catch (Exception e) {
            executionRepository.finish(executionId, "FAILED", null, e.getMessage());
            throw e;
        }
        todo.setStatus("COMPLETED");
        todo.setUpdatedAt(LocalDateTime.now());
        executionRepository.updateProgress(executionId, dynamicContext.getStep(), dynamicContext.getStep(),
                JSON.toJSONString(Map.of("todos", dynamicContext.getTodos())));
        sendTodoEvent(emitter, executionId, sessionId, dynamicContext.getTodos());
        log.info("测试结果:{}", apply);
        if (apply != null && !apply.isBlank()) {
            messageRecorder.recordAssistant(sessionId, agentId, 0, 0, apply, null);
        }
        executionRepository.finish(executionId, "COMPLETED", apply, null);


        // 发送完成标识
        try {
            AutoAgentExecuteResultEntity completeResult = AutoAgentExecuteResultEntity.createCompleteResult(requestParameter.getSessionId());
            // 发送SSE格式的数据
            String sseData = "data: " + JSON.toJSONString(completeResult) + "\n\n";
            emitter.send(sseData);
        } catch (Exception e) {
            log.error("发送完成标识失败：{}", e.getMessage(), e);
        }
    }

    private void sendTodoEvent(ResponseBodyEmitter emitter, String executionId, String sessionId,
                               List<AgentTodoItem> todos) {
        try {
            emitter.send("data: " + JSON.toJSONString(Map.of(
                    "type", "todo_updated", "executionId", executionId,
                    "sessionId", sessionId, "todos", todos)) + "\n\n");
        } catch (Exception e) {
            log.debug("发送 Todo 事件失败: {}", e.getMessage());
        }
    }

    @Override
    public String getType() {
        return AiAgentModeEnum.AUTO.getCode();
    }

    private String withConversationContext(java.util.List<HistoryMessage> history, String currentMessage) {
        if (history == null || history.isEmpty()) return currentMessage;
        StringBuilder context = new StringBuilder("以下是同一会话的短期记忆，请结合它处理最新请求：\n");
        int start = Math.max(0, history.size() - 20);
        for (int i = start; i < history.size(); i++) {
            HistoryMessage message = history.get(i);
            context.append(message.getRole()).append(": ").append(message.getContent()).append('\n');
        }
        return context.append("user: ").append(currentMessage).toString();
    }
}
