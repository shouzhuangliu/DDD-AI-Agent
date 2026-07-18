package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 执行总结节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:45
 */
@Slf4j
@Service
public class Step4LogExecutionSummaryNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n📊 === 执行第 {} 步 ===", dynamicContext.getStep());

        // 第四阶段：执行总结
        log.info("\n📊 阶段4: 执行总结分析");

        // 记录执行总结
        logExecutionSummary(dynamicContext.getMaxStep(), dynamicContext.getExecutionHistory(), dynamicContext.isCompleted());

        // 生成最终总结报告（无论任务是否完成都需要生成）
        generateFinalReport(requestParameter, dynamicContext);

        log.info("\n🏁 === 动态多轮执行结束 ====");

        String finalSummary = dynamicContext.getValue("finalSummary");
        if (finalSummary != null && !finalSummary.isBlank()) {
            return finalSummary;
        }
        return "任务已执行，但未能生成可展示的最终答复。请重试或查看执行日志。";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 总结节点是最后一个节点，返回null表示执行结束
        return defaultStrategyHandler;
    }

    /**
     * 记录执行总结
     */
    private void logExecutionSummary(int maxSteps, StringBuilder executionHistory, boolean isCompleted) {
        log.info("\n📊 === 动态多轮执行总结 ====");

        int actualSteps = Math.min(maxSteps, executionHistory.toString().split("=== 第").length - 1);
        log.info("📈 总执行步数: {} 步", actualSteps);

        if (isCompleted) {
            log.info("✅ 任务完成状态: 已完成");
        } else {
            log.info("⏸️ 任务完成状态: 未完成（达到最大步数限制）");
        }

        // 计算执行效率
        double efficiency = isCompleted ? 100.0 : (double) actualSteps / maxSteps * 100;
        log.info("📊 执行效率: {}%", efficiency);
    }

    /**
     * 生成最终总结报告
     */
    private void generateFinalReport(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            // 快速通道（简单意图）已经直接生成了最终回复并推送，无需再调用模型重新总结
            String existingSummary = dynamicContext.getValue("finalSummary");
            if (existingSummary != null && !existingSummary.isBlank()) {
                log.info("检测到 finalSummary 已存在（快速通道），跳过重新总结: {}", existingSummary);
                return;
            }

            boolean isCompleted = dynamicContext.isCompleted();
            log.info("\n--- 生成{}任务的最终答案 ---", isCompleted ? "已完成" : "未完成");

            String summaryPrompt = getSummaryPrompt(requestParameter, dynamicContext, isCompleted);

            // 获取对话客户端 - 使用任务分析客户端进行总结
            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode());
            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId(), requestParameter.getModelId());

            String summaryResult = chatClient
                    .prompt(summaryPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId() + "-summary")
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50))
                    .call().content();

            assert summaryResult != null;
            logFinalReport(dynamicContext, summaryResult, requestParameter.getSessionId());

            // 将总结结果保存到动态上下文中
            dynamicContext.setValue("finalSummary", summaryResult);

        } catch (Exception e) {
            log.error("生成最终总结报告时出现异常: {}", e.getMessage(), e);
        }
    }

    private static String getSummaryPrompt(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, boolean isCompleted) {
        String summaryPrompt;
        if (isCompleted) {
            summaryPrompt = String.format("""
                    你是中文企业网站里的智能体，请面向用户输出最终答复。

                    用户原始消息：%s

                    内部执行记录（仅供你判断，不要照抄给用户）：
                    %s

                    输出要求：
                    1. 全部使用简体中文。
                    2. 直接回答用户，不要输出“执行总结”“任务完成”“完成率”“ANALYSIS_*”等内部流程信息。
                    3. 如果用户消息过短或意图不明确，例如“1”“hi”“测试”，请礼貌说明需要用户补充具体问题。
                    4. 如果已经得到可用结果，给出清晰、具体、可执行的答案。
                    5. 如果内部执行记录不足以回答，只说明缺少哪些关键信息，并给出下一步建议。

                    请输出最终给用户看的中文答复：
                    """,
                    requestParameter.getMessage(),
                    dynamicContext.getExecutionHistory().toString());
        } else {
            summaryPrompt = String.format("""
                    你是中文企业网站里的智能体。任务没有完全执行完成，但仍需要给用户一个可读的中文答复。

                    用户原始消息：%s

                    内部执行记录（仅供你判断，不要照抄给用户）：
                    %s

                    输出要求：
                    1. 全部使用简体中文。
                    2. 不要输出“执行总结”“任务未完成”“完成率”“ANALYSIS_*”等内部流程信息。
                    3. 如果用户消息过短或意图不明确，例如“1”“hi”“测试”，请礼貌说明需要用户补充具体问题。
                    4. 先给出已经能确定的内容，再说明还缺哪些信息。
                    5. 给出用户下一步可以怎么补充，而不是展示内部执行过程。

                    请输出最终给用户看的中文答复：
                    """,
                    requestParameter.getMessage(),
                    dynamicContext.getExecutionHistory().toString());
        }
        return summaryPrompt;
    }

    /**
     * 输出最终总结报告
     */
    private void logFinalReport(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String summaryResult, String sessionId) {
        boolean isCompleted = dynamicContext.isCompleted();
        log.info("\n📋 === {}任务最终总结报告 ===", isCompleted ? "已完成" : "未完成");

        String[] lines = summaryResult.split("\n");
        String currentSection = "summary_overview";
        StringBuilder sectionContent = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 检测是否开始新的总结部分
            String newSection = detectSummarySection(line);
            if (newSection != null && !newSection.equals(currentSection)) {
                // 发送前一个部分的内容
                if (!sectionContent.isEmpty()) {
                    sendSummarySubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                }
                currentSection = newSection;
                sectionContent.setLength(0);
            }

            // 收集当前部分的内容
            if (!sectionContent.isEmpty()) {
                sectionContent.append("\n");
            }
            sectionContent.append(line);

            // 根据内容类型添加不同图标
            if (line.contains("已完成") || line.contains("完成的工作")) {
                log.info("✅ {}", line);
            } else if (line.contains("未完成") || line.contains("原因")) {
                log.info("❌ {}", line);
            } else if (line.contains("建议") || line.contains("推荐")) {
                log.info("💡 {}", line);
            } else if (line.contains("评估") || line.contains("效果")) {
                log.info("📊 {}", line);
            } else {
                log.info("📝 {}", line);
            }
        }

        // 发送最后一个部分的内容
        if (!sectionContent.isEmpty()) {
            sendSummarySubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
        }

        // 发送完整的总结结果
        sendSummaryResult(dynamicContext, summaryResult, sessionId);

        // 发送完成标识
        sendCompleteResult(dynamicContext, sessionId);
    }

    /**
     * 发送总结结果到流式输出
     */
    private void sendSummaryResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                   String summaryResult, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(
                summaryResult, sessionId);
        sendSseResult(dynamicContext, result);
    }

    /**
     * 发送总结阶段细分结果到流式输出
     */
    private void sendSummarySubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                      String subType, String content, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummarySubResult(
                subType, content, sessionId);
        sendSseResult(dynamicContext, result);
    }

    /**
     * 发送完成标识到流式输出
     */
    private void sendCompleteResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
        sendSseResult(dynamicContext, result);
        log.info("✅ 已发送完成标识");
    }

    /**
     * 检测总结部分标识
     */
    private String detectSummarySection(String content) {
        if (content.contains("已完成的工作") || content.contains("完成的工作") || content.contains("工作内容和成果")) {
            return "completed_work";
        } else if (content.contains("未完成的原因") || content.contains("未完成原因")) {
            return "incomplete_reasons";
        } else if (content.contains("关键因素") || content.contains("完成的关键因素")) {
            return "key_factors";
        } else if (content.contains("执行效率") || content.contains("执行效率和质量")) {
            return "efficiency_quality";
        } else if (content.contains("完成剩余任务的建议") || content.contains("建议") || content.contains("优化建议") || content.contains("经验总结")) {
            return "suggestions";
        } else if (content.contains("整体执行效果") || content.contains("评估")) {
            return "evaluation";
        }
        return null;
    }

}
