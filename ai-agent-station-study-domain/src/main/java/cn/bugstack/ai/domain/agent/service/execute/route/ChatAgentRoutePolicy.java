package cn.bugstack.ai.domain.agent.service.execute.route;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class ChatAgentRoutePolicy {

    private static final Pattern GREETING = Pattern.compile(
            "^(你好|您好|哈哈哈|hello|hi|hey|谢谢|感谢|好的|ok|收到|嗯嗯|再见|拜拜)[\\s!！。?.？,，]*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TINY_INPUT = Pattern.compile("^[\\p{IsHan}\\w\\d]{1,3}$");

    private static final String[] REACT_HINTS = {
            "查询", "查一个", "搜索", "检索", "调用", "读取", "获取", "看看", "mcp", "api", "数据库", "订单", "库存", "日志"
    };

    private static final String[] INVESTIGATION_HINTS = {
            "帮我查", "帮我看", "帮我查看", "排查", "查看项目", "查看代码", "看代码", "运行", "执行命令", "跑命令", "bash", "日志"
    };

    private static final String[] FEEDBACK_HINTS = {
            "遇到问题", "出了问题", "有个问题", "反馈", "不一致", "异常", "报错", "不好用", "bug", "不对", "失败", "超时",
            "我发现", "存在", "缺货", "补货", "空缺商品", "没货", "漏洞", "显示没货", "业务存在", "希望补货", "希望修复", "空缺"
    };

    private static final String[] PLAN_HINTS = {
            "计划", "规划", "方案", "步骤", "路线图", "拆解", "先制定", "不要直接执行"
    };

    private static final String[] AUTO_HINTS = {
            "执行", "修复", "完成", "实现", "开发", "验证", "部署", "生成", "上传", "发布", "跑起来", "处理"
    };

    public RouteDecision route(String message, String preferredMode) {
        String text = normalize(message);
        if (text.isBlank()) {
            return new RouteDecision("chat", "空输入或低意图输入，走普通聊天。");
        }
        if (isChat(text)) {
            return new RouteDecision("chat", "问候、确认或极短输入，先由 Chat 协调器直接回复。");
        }
        if (containsAny(text, FEEDBACK_HINTS) && !containsAny(text, INVESTIGATION_HINTS)) {
            return new RouteDecision("feedback", "用户在描述业务问题或使用反馈，先沉淀 Feedback 并进入评测队列。");
        }
        if (containsAny(text, PLAN_HINTS)
                && (!containsAny(text, AUTO_HINTS) || text.contains("不要直接执行") || text.contains("先制定"))) {
            return new RouteDecision("plan", "用户强调先规划，进入 Plan 协调。");
        }
        if (containsAny(text, AUTO_HINTS) && hasMultiStepSignal(text)) {
            return new RouteDecision("auto", "存在多步骤执行与验证诉求，进入 Auto 执行链。");
        }
        if (containsAny(text, REACT_HINTS) || containsAny(text, INVESTIGATION_HINTS)) {
            return new RouteDecision("react", "需要工具、外部数据或排查动作，进入 ReAct。");
        }
        return new RouteDecision("chat", "没有明确工具或执行意图，优先由 Chat 回复。");
    }

    private static boolean isChat(String text) {
        return GREETING.matcher(text).matches() || TINY_INPUT.matcher(text).matches();
    }

    private static boolean hasMultiStepSignal(String text) {
        int count = 0;
        if (containsAny(text, AUTO_HINTS)) count++;
        if (text.contains("并") || text.contains("然后") || text.contains("再") || text.contains("同时")) count++;
        if (containsAny(text, PLAN_HINTS) || containsAny(text, REACT_HINTS)) count++;
        return count >= 2 || text.length() >= 28;
    }

    private static boolean containsAny(String text, String[] words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record RouteDecision(String route, String reason) {
    }
}
