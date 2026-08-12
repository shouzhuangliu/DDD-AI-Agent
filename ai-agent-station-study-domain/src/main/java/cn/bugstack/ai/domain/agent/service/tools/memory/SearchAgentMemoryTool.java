package cn.bugstack.ai.domain.agent.service.tools.memory;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import com.alibaba.fastjson2.JSON;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SearchAgentMemoryTool extends AbstractReActTool {
    private final AgentMemoryCatalogPort catalog;

    public SearchAgentMemoryTool(AgentMemoryCatalogPort catalog) { this.catalog = catalog; }

    @Tool(name = "search_agent_memory", description = "搜索当前 Agent 已审核发布的长期记忆索引，仅返回标题、摘要和 memoryId，不返回正文")
    public String search(@ToolParam(description = "要检索的业务问题或关键词") String query,
                         @ToolParam(description = "返回数量，最大 5") Integer limit) {
        ReActToolContext context = ReActToolContextHolder.get();
        if (context == null || context.getAgentId() == null) return "ERROR: missing agent context";
        emitAction("search_agent_memory", "搜索当前 Agent 的已发布长期记忆");
        var result = catalog.search(context.getAgentId(), query, Math.min(limit == null ? 5 : Math.max(limit, 1), 5));
        emitObservation("search_agent_memory", "找到 " + result.size() + " 条记忆索引");
        return JSON.toJSONString(result);
    }
}
