package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.domain.agent.service.execute.route.ChatAgentRoutePolicy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatAgentRoutePolicyTest {

    @Test
    public void routesGreetingsAndTinyInputsToChat() {
        ChatAgentRoutePolicy policy = new ChatAgentRoutePolicy();

        assertEquals("chat", policy.route("你好", "auto").route());
        assertEquals("chat", policy.route("1", "react").route());
        assertEquals("chat", policy.route("谢谢", "auto").route());
    }

    @Test
    public void routesToolOrExternalDataRequestsToReact() {
        ChatAgentRoutePolicy policy = new ChatAgentRoutePolicy();

        assertEquals("react", policy.route("帮我查一下订单状态", "auto").route());
        assertEquals("react", policy.route("调用 MCP 查询库存", "auto").route());
    }

    @Test
    public void routesPlanningAndMultiStepRequestsToPlanOrAuto() {
        ChatAgentRoutePolicy policy = new ChatAgentRoutePolicy();

        assertEquals("plan", policy.route("先制定一个上线检查计划，不要直接执行", "auto").route());
        assertEquals("auto", policy.route("帮我分析日志、修复问题并验证结果", "react").route());
    }

    @Test
    public void routesBusinessIssueReportsToFeedbackUnlessInvestigationIsRequested() {
        ChatAgentRoutePolicy policy = new ChatAgentRoutePolicy();

        assertEquals("feedback", policy.route("我目前遇到了一个问题你们的 db 有缓存不一致，具体商品是显卡5060", "react").route());
        assertEquals("react", policy.route("帮我查看项目代码并排查 db 缓存不一致的问题", "react").route());
    }
}
