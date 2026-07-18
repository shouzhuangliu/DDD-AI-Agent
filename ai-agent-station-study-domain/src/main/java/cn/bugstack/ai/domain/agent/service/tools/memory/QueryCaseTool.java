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
public class QueryCaseTool extends AbstractReActTool {

    @Resource
    private ChatMessageRecorder recorder;

    @Tool(description = "查询已解决的Case案例库,返回高频Case列表。当用户问常见问题或以前遇到过时调用。")
    public String queryCases(@ToolParam(description = "搜索关键词,如login/403/disk,选填") String keyword,
                             @ToolParam(description = "返回条数,默认5") Integer limit) {
        String toolName = "query_cases";
        emitAction(toolName, "查询Case: " + (keyword != null ? keyword : "全部"));
        try {
            Object result = recorder.queryCases(keyword, limit != null ? limit : 5);
            String json = JSON.toJSONString(result);
            emitObservation(toolName, "查询到" + (result instanceof java.util.List ? ((java.util.List<?>)result).size() : "0") + "条");
            return json;
        } catch (Exception e) {
            String msg = "查询Case失败: " + e.getMessage();
            log.warn(msg);
            emitObservation(toolName, msg);
            return msg;
        }
    }
}