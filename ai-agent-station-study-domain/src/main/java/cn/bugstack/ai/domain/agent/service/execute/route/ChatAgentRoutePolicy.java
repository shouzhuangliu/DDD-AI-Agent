package cn.bugstack.ai.domain.agent.service.execute.route;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chat 与工具型 Agent 的轻量路由策略。
 *
 * <p>路由只决定“是否需要执行能力”，不负责判断反馈是否升级为 Case：
 * 业务反馈仍由 Feedback 评测链路处理；只有明确查询外部数据时才进入 ReAct，
 * 从而避免用户随口描述一个问题就触发项目扫描。</p>
 */
@Service
public class ChatAgentRoutePolicy {

    private static final Pattern GREETING = Pattern.compile(
            "^(你好|您好|哈哈哈|hello|hi|hey|谢谢|感谢|好的|ok|收到|嗨|再见|拜拜|浣犲ソ|璋㈣阿)[\\s!！。.?？,，!！]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TINY_INPUT = Pattern.compile("^[\\p{IsHan}\\w\\d]{1,3}$");

    private static final String[] QUERY_HINTS = {
            "查询", "查看", "拉取", "获取", "抓取", "列出", "汇总", "统计", "检索", "搜索", "查一下", "帮我查",
            "鏌ヨ", "鏌ョ湅", "鑾峰彇", "鎼滅储"
    };
    private static final String[] FEEDBACK_SCOPE_HINTS = {
            "反馈", "今日反馈", "今天反馈", "最近反馈", "最新反馈", "反馈记录", "用户反馈", "运维反馈", "反馈数据", "反馈列表",
            "鍙嶉", "鍙嶉璁板綍", "鐢ㄦ埛鍙嶉"
    };
    private static final String[] REACT_HINTS = {
            "查询", "查看", "拉取", "获取", "抓取", "调用", "读取", "搜索", "检索", "运行命令",
            "数据库", "订单", "库存", "接口", "日志", "数据", "MCP", "工具",
            "鏌ヨ", "鏌ヤ竴涓嬧", "鎼滅储", "妫€绱", "璋冪敤", "璇诲彇", "鐪嬩竴涓", "mcp", "api", "鏁版嵁搴", "璁㈠崟", "搴撳瓨", "鏃ュ織"
    };
    private static final String[] INVESTIGATION_HINTS = {
            "帮我排查", "帮我查看项目", "查看项目", "查看代码", "读取代码", "运行命令", "执行命令", "排查", "调试",
            "甯垜鏌", "鏌ョ湅椤圭爴", "鏌ョ湅浠ｇ爴", "鐪嬩唬鐮", "杩愯", "鎵ц鍛戒护", "bash"
    };
    private static final String[] FEEDBACK_HINTS = {
            "遇到问题", "出了问题", "有个问题", "反馈", "不一致", "异常", "报错", "不好用", "失败",
            "缺货", "缺少商品", "空缺商品", "补货", "库存不足", "库存不一致", "希望修复",
            "閬囧埌闂", "鍑轰簡闂", "鏈変釜闂", "鍙嶉", "涓嶄竴鑷", "寮傚父", "鎶ラ敊", "涓嶅", "澶辫触", "缂鸿揣", "琛ヨ揣", "绌虹己"
    };
    private static final String[] SKILL_TRIAGE_HINTS = {
            "分诊", "评测", "评估", "业务skills", "业务 skill", "结合skills", "结合 skill",
            "升级case", "升级 Case", "候选case", "候选 Case", "判定case", "判定 Case", "巡检"
    };
    private static final String[] PLAN_HINTS = {
            "计划", "规划", "方案", "步骤", "路线图", "拆解", "先制定", "不要直接执行",
            "璁″垝", "瑙勫垝", "鏂规", "姝ラ", "璺嚎鍥", "鎷嗚В", "鍏堝埗瀹", "涓嶈鐩存帴鎵ц"
    };
    private static final String[] AUTO_HINTS = {
            "执行", "修复", "完成", "实现", "开发", "验证", "部署", "生成", "上传", "发布", "跑起来", "处理",
            "鎵ц", "淇", "瀹屾垚", "瀹炵幇", "寮€鍙", "楠岃瘉", "閮ㄧ讲", "鐢熸垚", "涓婁紶", "鍙戝竷", "璺戣捣鏉", "澶勭悊"
    };

    public RouteDecision route(String message, String preferredMode) {
        String text = normalize(message);
        if (text.isBlank()) {
            return new RouteDecision("chat", "输入为空，使用普通 Chat。");
        }
        if (isChat(text)) {
            return new RouteDecision("chat", "问候、确认或极短输入，使用普通 Chat 直接回复。");
        }
        // 查询类反馈必须优先于“反馈收集”：这是只读数据查询，不是让用户提交问题。
        if (isFeedbackQuery(text)) {
            return new RouteDecision("react", "命中反馈查询意图，使用当前 Agent 已绑定的 MCP 工具读取数据并汇总。");
        }
        // 业务 Skill 分诊/评测需要读取绑定 Skill 和 MCP 事实，不能被当成普通反馈录入。
        if (containsAny(text, SKILL_TRIAGE_HINTS)
                && ("feedback-ops".equalsIgnoreCase(preferredMode)
                || containsAny(text, new String[]{"业务", "反馈", "case", "skill", "巡检"}))) {
            return new RouteDecision("react", "命中业务 Skill 分诊/Case 评测意图，进入 ReAct 读取事实并输出候选 Case。");
        }
        if (containsAny(text, FEEDBACK_HINTS) && !containsAny(text, INVESTIGATION_HINTS)) {
            return new RouteDecision("feedback", "识别为业务反馈，先记录 Feedback 并进入评测队列，不主动读取项目代码。");
        }
        if (containsAny(text, PLAN_HINTS)
                && (!containsAny(text, AUTO_HINTS) || text.contains("不要直接执行") || text.contains("鍏堝埗瀹"))) {
            return new RouteDecision("plan", "用户强调先规划，进入 Plan 协调。");
        }
        if (containsAny(text, AUTO_HINTS) && hasMultiStepSignal(text)) {
            return new RouteDecision("auto", "存在多步骤执行与验证诉求，进入 Auto 执行链。");
        }
        if (containsAny(text, REACT_HINTS) || containsAny(text, INVESTIGATION_HINTS)) {
            return new RouteDecision("react", "需要工具、外部数据或排查动作，进入 ReAct。");
        }
        return new RouteDecision("chat", "没有明确工具或执行意图，优先使用 Chat 回复。");
    }

    private static boolean isFeedbackQuery(String text) {
        boolean hasQuery = containsAny(text, QUERY_HINTS);
        if (!hasQuery) return false;
        if (containsAny(text, new String[]{"今日", "今天", "最近", "最新", "反馈记录", "反馈数据", "反馈列表"})) return true;
        return containsAny(text, FEEDBACK_SCOPE_HINTS);
    }

    private static boolean isChat(String text) {
        return GREETING.matcher(text).matches() || TINY_INPUT.matcher(text).matches();
    }

    private static boolean hasMultiStepSignal(String text) {
        int count = 0;
        if (containsAny(text, AUTO_HINTS)) count++;
        if (containsAny(text, new String[]{"然后", "接着", "同时", "骞", "鐒跺悗", "鍐"})) count++;
        if (containsAny(text, PLAN_HINTS) || containsAny(text, REACT_HINTS)) count++;
        return count >= 2 || text.length() >= 28;
    }

    private static boolean containsAny(String text, String[] words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record RouteDecision(String route, String reason) {
    }
}
