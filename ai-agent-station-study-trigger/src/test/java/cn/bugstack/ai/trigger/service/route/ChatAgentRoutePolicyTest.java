package cn.bugstack.ai.trigger.service.route;

import cn.bugstack.ai.domain.agent.service.execute.route.ChatAgentRoutePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAgentRoutePolicyTest {

    private final ChatAgentRoutePolicy policy = new ChatAgentRoutePolicy();

    @Test
    void routesBusinessProblemDescriptionToFeedbackEvenWhenAgentPrefersReact() {
        ChatAgentRoutePolicy.RouteDecision decision = policy.route("你好我发现咱们业务存在一个空缺商品，希望补货", "react");

        assertEquals("feedback", decision.route());
        assertTrue(decision.reason().contains("Feedback"));
    }

    @Test
    void routesExplicitProjectInvestigationToReact() {
        ChatAgentRoutePolicy.RouteDecision decision = policy.route("帮我查看项目代码并排查这个缓存不一致问题", "react");

        assertEquals("react", decision.route());
    }

    @Test
    void keepsShortGreetingInChatEvenWhenAgentPrefersReact() {
        ChatAgentRoutePolicy.RouteDecision decision = policy.route("hi", "react");

        assertEquals("chat", decision.route());
    }

    @Test
    void keepsNormalConversationInChatWhenNoToolIntentExists() {
        ChatAgentRoutePolicy.RouteDecision decision = policy.route("我想了解一下这个 Agent 主要负责什么业务", "react");

        assertEquals("chat", decision.route());
    }
}
