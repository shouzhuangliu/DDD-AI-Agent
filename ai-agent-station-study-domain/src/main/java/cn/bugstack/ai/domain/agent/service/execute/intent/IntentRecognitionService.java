package cn.bugstack.ai.domain.agent.service.execute.intent;

import cn.bugstack.ai.domain.agent.model.valobj.TaskProfile;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 三层意图识别服务。
 * <p>
 * 第1层 — 关键词匹配(快): 问候/感谢/简单问答 直接走快速通道
 * 第2层 — 模式识别(经验): 查历史 Case/Pattern 是否匹配
 * 第3层 — 语义理解(精准): LLM 分类 + 任务画像
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Service
public class IntentRecognitionService {

    // ========== 第1层: 关键词匹配 ==========

    private static final List<Pattern> GREETING_PATTERNS = List.of(
            Pattern.compile("^(你好|您好|嗨|hi|hello|hey|早上好|下午好|晚上好)[\\s!.,;:?]*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(谢谢|感谢|thanks|thank you|thx)[\\s!.,;:?]*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(再见|拜拜|bye|goodbye|see you)[\\s!.,;:?]*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(你是谁|who are you|你叫什么|你的名字)[\\s?]*$", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> SIMPLE_QA_PATTERNS = List.of(
            Pattern.compile("^[\\d]+[\\+\\-\\*\\/][\\d]+[=]?[\\d]*$"),   // 1+1, 3*4
            Pattern.compile("^(今天星期几|几点了|现在几点|日期|time|date|weather|天气)"),
            Pattern.compile("^[是还]\\s*$"),                                 // 是/否 单个字
            Pattern.compile("^[\\w\\W]{1,5}$")                              // 极短输入(1-5字符)
    );

    /** 快速通道: 匹配问候/简单问答 */
    public TaskProfile layer1KeywordMatch(String message) {
        if (message == null || message.isBlank()) {
            return TaskProfile.quickReply("UNKNOWN", "空输入");
        }
        String trimmed = message.trim();

        for (Pattern p : GREETING_PATTERNS) {
            if (p.matcher(trimmed).matches()) {
                return TaskProfile.quickReply("GREETING", "关键词匹配: 问候/感谢");
            }
        }
        for (Pattern p : SIMPLE_QA_PATTERNS) {
            if (p.matcher(trimmed).matches()) {
                return TaskProfile.quickReply("SIMPLE_QA", "关键词匹配: 简单问答");
            }
        }
        return null; // 未匹配,进入下一层
    }

    // ========== 第2层: 模式识别(从历史 Case 匹配) ==========

    /**
     * 模式识别: 检查输入是否匹配已有 Case。
     * 由调用方注入历史 Case 关键词列表。
     */
    public TaskProfile layer2PatternMatch(String message, List<String> caseKeywords) {
        if (message == null || caseKeywords == null || caseKeywords.isEmpty()) {
            return null;
        }
        String lowered = message.toLowerCase();
        for (String keyword : caseKeywords) {
            if (keyword != null && lowered.contains(keyword.toLowerCase())) {
                log.info("模式匹配: 命中关键字 '{}'", keyword);
                return TaskProfile.reactMode("SEARCH", "模式匹配: " + keyword);
            }
        }
        return null;
    }

    // ========== 第3层: 语义理解 ==========

    private static final String CLASSIFY_PROMPT = """
            你是一个任务分类器。请分析用户输入,返回 JSON 格式的分类结果,不要任何解释。
            {
              "intent": "GREETING|SIMPLE_QA|SEARCH|TASK|UNKNOWN",
              "needsExternalData": true/false,
              "needsMultiStep": true/false,
              "inputComplexity": "SIMPLE|MEDIUM|COMPLEX",
              "hasDependencies": true/false,
              "reason": "简短说明"
            }

            分类规则:
            - intents: GREETING=问候/感谢, SIMPLE_QA=单句问答, SEARCH=搜索查询, TASK=需要执行的任务, UNKNOWN=不确定
            - needsExternalData: 需要查文件/查数据库/调API/访问网络时为true
            - needsMultiStep: 需要多步操作(分析→执行→检查)时为true
            - inputComplexity: 单句简单→SIMPLE, 多句要求→MEDIUM, 长文/多需求→COMPLEX
            - hasDependencies: 步骤间有先后依赖时为true

            用户输入: %s
            """;

    public TaskProfile layer3Semantic(String message, ChatClient judgeClient) {
        try {
            String prompt = String.format(CLASSIFY_PROMPT, message);
            String result = judgeClient.prompt(prompt).call().content();

            if (result != null) {
                // 提取 JSON (LLM 可能用 ```json 包裹)
                String jsonStr = result;
                if (jsonStr.contains("```json")) {
                    jsonStr = jsonStr.substring(jsonStr.indexOf("```json") + 7);
                    if (jsonStr.contains("```")) jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
                } else if (jsonStr.contains("```")) {
                    jsonStr = jsonStr.substring(jsonStr.indexOf("```") + 3);
                    if (jsonStr.contains("```")) jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
                }
                jsonStr = jsonStr.trim();

                var parsed = JSON.parseObject(jsonStr);
                String intent = parsed.getString("intent");
                boolean extData = parsed.getBooleanValue("needsExternalData");
                boolean multiStep = parsed.getBooleanValue("needsMultiStep");
                String complexity = parsed.getString("inputComplexity");
                boolean deps = parsed.getBooleanValue("hasDependencies");
                String reason = parsed.getString("reason");

                // 根据画像选择模式
                String mode;
                if (!extData && "SIMPLE".equals(complexity)) {
                    mode = "quick";
                } else if (multiStep || deps) {
                    mode = "auto";
                } else {
                    mode = "react";
                }

                return TaskProfile.builder()
                        .intent(intent != null ? intent : "UNKNOWN")
                        .needsExternalData(extData)
                        .needsMultiStep(multiStep)
                        .inputComplexity(complexity != null ? complexity : "SIMPLE")
                        .hasDependencies(deps)
                        .suggestedMode(mode)
                        .reason("语义理解: " + (reason != null ? reason : ""))
                        .build();
            }
        } catch (Exception e) {
            log.warn("语义理解失败: {}", e.getMessage());
        }
        // 降级: 复杂任务安全处理
        return TaskProfile.autoMode("TASK", "语义理解降级: 按复杂任务处理");
    }

    /**
     * 三层识别管道:
     * 关键词 → 模式匹配 → 语义理解
     */
    public TaskProfile recognize(String message, List<String> caseKeywords, ChatClient judgeClient) {
        // L1: 关键词(最快)
        TaskProfile l1 = layer1KeywordMatch(message);
        if (l1 != null) {
            log.info("意图识别[L1关键词]: {} -> {}", message, l1.getIntent());
            return l1;
        }

        // L2: 模式匹配
        TaskProfile l2 = layer2PatternMatch(message, caseKeywords);
        if (l2 != null) {
            log.info("意图识别[L2模式]: {} -> {}", message, l2.getIntent());
            return l2;
        }

        // L3: 语义理解
        TaskProfile l3 = layer3Semantic(message, judgeClient);
        log.info("意图识别[L3语义]: {} -> {}", message, l3.getIntent());
        return l3;
    }
}