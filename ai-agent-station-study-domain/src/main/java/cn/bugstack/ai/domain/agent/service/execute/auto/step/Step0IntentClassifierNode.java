package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.TaskProfile;
import cn.bugstack.ai.domain.agent.service.execute.auto.state.AutoAgentStateEnum;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.intent.IntentRecognitionService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图识别与任务分类节点(Step0) — 三层意图 + 四维度分类。
 * <p>
 * L1 关键词(快): 问候/感谢/简单问答
 * L2 模式(经验): 匹配已有 Case
 * L3 语义(精准): LLM 分类 + TaskProfile
 * <p>
 * 决策结果:
 * - quick → 快速通道
 * - react → ReAct 工具循环
 * - auto → 完整多步链路
 */
@Slf4j
@Service("step0IntentClassifierNode")
public class Step0IntentClassifierNode extends AbstractExecuteSupport {

    @Resource
    private IntentRecognitionService intentRecognitionService;

    private static final String DEFAULT_QUICK_CLIENT_ID = "3105";

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n🧭 === 意图识别 + 任务分类 ===");

        String message = requestParameter.getMessage();
        if (message == null || message.trim().isEmpty()) {
            dynamicContext.setValue("intent", "SIMPLE");
            return router(requestParameter, dynamicContext);
        }

        AiAgentClientFlowConfigVO assistantVO = dynamicContext.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode());
        ChatClient judgeClient = getChatClientByClientId(assistantVO.getClientId(), requestParameter.getModelId());

        @SuppressWarnings("unchecked")
        List<String> caseKeywords = dynamicContext.getValue("caseKeywords");

        TaskProfile profile = intentRecognitionService.recognize(message, caseKeywords, judgeClient);

        log.info("🧭 任务画像: intent={} extData={} multiStep={} complexity={} deps={} mode={}",
                profile.getIntent(), profile.isNeedsExternalData(), profile.isNeedsMultiStep(),
                profile.getInputComplexity(), profile.isHasDependencies(), profile.getSuggestedMode());

        dynamicContext.setValue("taskProfile", profile);
        dynamicContext.setValue("intent", profile.getIntent());

        switch (profile.getSuggestedMode()) {
            case "quick":
                handleQuickReply(profile, requestParameter, dynamicContext);
                break;
            case "react":
                dynamicContext.setValue("mode", "react");
                log.info("🧠 建议 ReAct 模式");
                break;
            case "auto":
                break;
        }

        dynamicContext.setCurrentState(AutoAgentStateEnum.INTENT.next(dynamicContext));
        return router(requestParameter, dynamicContext);
    }

    private void handleQuickReply(TaskProfile profile, ExecuteCommandEntity requestParameter,
                                  DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String sessionId = requestParameter.getSessionId();
        try {
            String qid = resolveQuickClientId(dynamicContext);
            ChatClient qc = getChatClientByClientId(qid, requestParameter.getModelId());
            String reply = qc.prompt(requestParameter.getMessage())
                    .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId).param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50))
                    .call().content();
            if (reply == null || reply.isBlank()) reply = "好的，已收到您的消息。";
            log.info("⚡ 快速通道: {}", reply);
            sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.createExecutionResult(dynamicContext.getStep(), reply, sessionId));
            sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.createSummaryResult(reply, sessionId));
            dynamicContext.getExecutionHistory().append("[快速回复] ").append(reply).append("\n");
            dynamicContext.setValue("finalSummary", reply);
            dynamicContext.setCompleted(true);
        } catch (Exception e) {
            log.error("快速通道失败: {}", e.getMessage());
        }
    }

    private String resolveQuickClientId(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dc) {
        try {
            AiAgentClientFlowConfigVO qv = dc.getAiAgentClientFlowConfigVOMap().get("QUICK_REPLY_CLIENT");
            if (qv != null && qv.getClientId() != null) return qv.getClientId();
        } catch (Exception ignored) {}
        return DEFAULT_QUICK_CLIENT_ID;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity rp, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dc) throws Exception {
        String nn = dc.getCurrentState().nodeName();
        return nn == null ? getBean("step4LogExecutionSummaryNode") : getBean(nn);
    }
}
