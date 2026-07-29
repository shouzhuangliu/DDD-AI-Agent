package cn.bugstack.ai.domain.agent.service.tools.subagent;

import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** ReAct 可调用的一级 Subagent 工具，Subagent 本身不暴露此工具。 */
@Component
public class SubagentTaskTool extends AbstractReActTool {

    private final SubagentExecutionService executionService;

    public SubagentTaskTool(SubagentExecutionService executionService) {
        this.executionService = executionService;
    }

    @Tool(description = "将复杂且相互独立的任务交给一个独立 Subagent 执行并返回结果。不要用于简单任务。")
    public String runSubagent(@ToolParam(description = "任务的简短名称") String description,
                              @ToolParam(description = "Subagent 要完成的具体任务") String prompt) {
        ReActToolContext context = ReActToolContextHolder.get();
        if (context == null) return "Subagent 上下文不存在";
        String toolName = "task";
        emitAction(toolName, "启动 Subagent: " + description);
        String result = executionService.submitAndWait(context, description, prompt);
        emitObservation(toolName, result);
        return result;
    }
}
