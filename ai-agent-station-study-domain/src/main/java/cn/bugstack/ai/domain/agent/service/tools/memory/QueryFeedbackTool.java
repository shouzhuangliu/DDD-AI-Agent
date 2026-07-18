package cn.bugstack.ai.domain.agent.service.tools.memory;

import cn.bugstack.ai.domain.agent.service.memory.ChatMessageRecorder;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QueryFeedbackTool extends AbstractReActTool {

    @Resource
    private ChatMessageRecorder recorder;

    @Tool(description = "查询用户反馈记录。当用户问最近反馈或用户说了什么时调用。")
    public String queryFeedback(@ToolParam(description = "查询条数,默认10") Integer limit,
                                @ToolParam(description = "按Agent筛选,选填") String agentId) {
        String toolName = "query_feedback";
        emitAction(toolName, "查询反馈: " + (agentId != null ? agentId : "全部"));
        try {
            Object result = recorder.queryFeedback(limit != null ? limit : 10, agentId);
            String json = JSON.toJSONString(result);
            emitObservation(toolName, "查询到反馈记录");
            return json;
        } catch (Exception e) {
            String msg = "查询反馈失败: " + e.getMessage();
            log.warn(msg);
            emitObservation(toolName, msg);
            return msg;
        }
    }
}