package cn.bugstack.ai.domain.agent.service.tools.memory;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import com.alibaba.fastjson2.JSON;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GetAgentMemoryTool extends AbstractReActTool {
    private final AgentMemoryCatalogPort catalog;

    public GetAgentMemoryTool(AgentMemoryCatalogPort catalog) { this.catalog = catalog; }

    @Tool(name = "get_agent_memory", description = "按 memoryId 读取当前 Agent 已审核发布的长期记忆正文，每次最多 3 条")
    public String get(@ToolParam(description = "memoryId JSON 数组，例如 [\"mem-1\"]") String memoryIdsJson) {
        ReActToolContext context = ReActToolContextHolder.get();
        if (context == null || context.getAgentId() == null) return "ERROR: missing agent context";
        List<String> ids;
        try { ids = JSON.parseArray(memoryIdsJson, String.class); }
        catch (Exception exception) { return "ERROR: memoryIds must be a JSON string array"; }
        ids = ids == null ? List.of() : ids.stream().filter(id -> id != null && !id.isBlank()).distinct().limit(3).toList();
        emitAction("get_agent_memory", "读取当前 Agent 的已发布长期记忆正文");
        var result = catalog.getPublished(context.getAgentId(), ids);
        emitObservation("get_agent_memory", "已读取 " + result.size() + " 条长期记忆正文");
        return JSON.toJSONString(result);
    }
}
