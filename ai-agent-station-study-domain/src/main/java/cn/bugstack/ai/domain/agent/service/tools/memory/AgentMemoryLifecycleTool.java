package cn.bugstack.ai.domain.agent.service.tools.memory;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryLifecyclePort;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import com.alibaba.fastjson2.JSON;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 仅允许主 Agent 为当前 Agent 写入可复用业务记忆。 */
@Component
public class AgentMemoryLifecycleTool extends AbstractReActTool {
    private final AgentMemoryLifecyclePort lifecycle;

    public AgentMemoryLifecycleTool(AgentMemoryLifecyclePort lifecycle) { this.lifecycle = lifecycle; }

    @Tool(name = "upsert_agent_memory", description = "将当前会话中有明确原文依据、可复用的业务规则或处理经验写入当前 Agent 的长期记忆。不得保存用户偏好、临时数据、模型或工具异常。")
    public String upsert(@ToolParam(description = "记忆类型：BUSINESS_RULE、OPERATING_PLAYBOOK 或 CAPABILITY_BOUNDARY") String memoryType,
                         @ToolParam(description = "稳定业务键，相同键会更新原记忆") String memoryKey,
                         @ToolParam(description = "简短标题") String title,
                         @ToolParam(description = "可复用的业务结论") String description,
                         @ToolParam(description = "结构化正文 JSON") String content,
                         @ToolParam(description = "重要性 0-100") Integer importance,
                         @ToolParam(description = "是否每次召回都优先提供") Boolean pinned,
                         @ToolParam(description = "支撑结论的当前会话消息 ID") Long messageId,
                         @ToolParam(description = "消息原文中的连续证据片段") String evidenceQuote,
                         @ToolParam(description = "创建或更新此记忆的原因") String reason) {
        ReActToolContext context = ReActToolContextHolder.get();
        if (context == null || context.getAgentId() == null || context.getSessionId() == null) return "ERROR: missing agent session context";
        emitAction("upsert_agent_memory", "保存当前 Agent 的长期业务记忆");
        AgentMemoryLifecyclePort.Result result = lifecycle.upsert(new AgentMemoryLifecyclePort.UpsertCommand(
                context.getAgentId(), memoryType, memoryKey, title, description, content,
                importance == null ? 50 : importance, Boolean.TRUE.equals(pinned), "MESSAGE", String.valueOf(messageId),
                evidenceQuote, reason));
        emitObservation("upsert_agent_memory", "长期记忆已" + result.operation() + "，memoryId=" + result.memoryId());
        return JSON.toJSONString(Map.of("memoryId", result.memoryId(), "version", result.version(), "operation", result.operation()));
    }

    @Tool(name = "retire_agent_memory", description = "将已失效或被新规则推翻的当前 Agent 长期记忆软删除；必须给出当前会话中的原文证据，不会物理删除历史审计。")
    public String retire(@ToolParam(description = "待失效记忆的 memoryId") String memoryId,
                         @ToolParam(description = "支撑失效结论的当前会话消息 ID") Long messageId,
                         @ToolParam(description = "消息原文中的连续证据片段") String evidenceQuote,
                         @ToolParam(description = "记忆失效的原因") String reason) {
        ReActToolContext context = ReActToolContextHolder.get();
        if (context == null || context.getAgentId() == null || context.getSessionId() == null) return "ERROR: missing agent session context";
        emitAction("retire_agent_memory", "软删除当前 Agent 的失效长期记忆");
        AgentMemoryLifecyclePort.Result result = lifecycle.retire(new AgentMemoryLifecyclePort.RetireCommand(
                context.getAgentId(), memoryId, "MESSAGE", String.valueOf(messageId), evidenceQuote, reason));
        emitObservation("retire_agent_memory", "长期记忆已软删除，memoryId=" + result.memoryId());
        return JSON.toJSONString(Map.of("memoryId", result.memoryId(), "operation", result.operation()));
    }
}
