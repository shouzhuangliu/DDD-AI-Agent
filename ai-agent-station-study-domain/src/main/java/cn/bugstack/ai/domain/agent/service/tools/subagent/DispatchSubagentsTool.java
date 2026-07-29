package cn.bugstack.ai.domain.agent.service.tools.subagent;

import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量并行委派工具。主 Agent 一次提交多个相互独立、可并行的子任务，服务端截断到
 * {@link SubagentExecutionService#MAX_CONCURRENT}（=3）并行执行、聚合结果。
 * <p>
 * 并行不依赖 Spring AI 同批 tool_calls 并发，完全由 {@link SubagentExecutionService} 双线程池保证。
 * 对齐 angx SubagentLimitMiddleware 的截断语义 + task_tool 的提交/等待。
 * <p>
 * Subagent 本身不暴露此工具（在子工具集中被剔除），禁止递归嵌套。
 *
 * @author ai-agent-station-study
 */
@Component
public class DispatchSubagentsTool extends AbstractReActTool {

    @Resource
    private SubagentExecutionService executionService;

    @Tool(description = "把多个相互独立、可并行的子任务一次性交给多个 Subagent 并行执行，返回每个子任务的结果。最多并行 3 个，超出会被截断。参数 tasksJson 为 JSON 数组，每项含 description 与 prompt。")
    public String dispatch_subagents(@ToolParam(description = "任务数组 JSON，例如 [{\"description\":\"查A\",\"prompt\":\"查询A的状态\"},...]") String tasksJson) {
        ReActToolContext ctx = ReActToolContextHolder.get();
        if (ctx == null) {
            return "Error: 当前无执行上下文，无法委派子 Agent。";
        }
        List<SubagentExecutionService.TaskInput> inputs = parse(tasksJson);
        if (inputs.isEmpty()) {
            return "Error: 未解析到任何子任务，请提供 tasksJson 数组。";
        }
        emitAction(ReActToolAllowlistPolicy.DISPATCH_SUBAGENTS, "并行 " + Math.min(inputs.size(), SubagentExecutionService.MAX_CONCURRENT) + " 个子任务");
        String result = executionService.dispatchAndWait(ctx, inputs);
        emitObservation(ReActToolAllowlistPolicy.DISPATCH_SUBAGENTS, result);
        return "Subagents result:\n" + result;
    }

    private List<SubagentExecutionService.TaskInput> parse(String tasksJson) {
        List<SubagentExecutionService.TaskInput> result = new ArrayList<>();
        if (tasksJson == null || tasksJson.isBlank()) return result;
        try {
            JSONArray array = JSON.parseArray(tasksJson);
            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj == null) continue;
                String description = obj.getString("description");
                String prompt = obj.getString("prompt");
                if (prompt == null || prompt.isBlank()) continue;
                result.add(new SubagentExecutionService.TaskInput(
                        description == null ? "子任务" + (i + 1) : description, prompt));
            }
        } catch (Exception e) {
            log.warn("dispatch_subagents 解析 tasksJson 失败: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void resetStep() {
        // 委派工具无内部步数计数。
    }
}
