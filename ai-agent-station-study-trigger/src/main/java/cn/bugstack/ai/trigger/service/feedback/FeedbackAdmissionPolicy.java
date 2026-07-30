package cn.bugstack.ai.trigger.service.feedback;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Automatic feedback admission should be strict: prefer missing vague signals
 * over polluting the business feedback pipeline with noise.
 */
@Component
public class FeedbackAdmissionPolicy {

    private static final Pattern TINY_OR_TEST = Pattern.compile(
            "^(\\d{1,6}|[a-zA-Z]{1,6}|[\\p{Punct}\\p{IsPunctuation}\\s]{1,20}|测试|test|hi|hello|ok|好的|可以|收到)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_MEANINGFUL_TOKEN = Pattern.compile("[\\p{IsHan}a-zA-Z]{2,}|\\d{3,}");

    private static final String[] PROBLEM_WORDS = {
            "问题", "异常", "错误", "失败", "报错", "不一致", "不对", "不好用", "超时", "bug", "故障", "投诉", "反馈",
            "缺货", "补货", "空缺", "漏了", "找不到", "没货"
    };

    private static final String[] BUSINESS_WORDS = {
            "订单", "商品", "库存", "缓存", "支付", "退款", "数据库", "db", "接口", "用户", "账号", "物流",
            "价格", "显卡", "业务", "内存", "型号", "sku", "页面"
    };

    private static final String[] EVIDENCE_WORDS = {
            "型号", "sku", "id", "订单号", "页面", "接口", "截图", "日志", "显卡", "内存", "ddr", "品牌"
    };

    public boolean shouldCapture(String message) {
        return analyze(message, Set.of()).capturable();
    }

    public boolean shouldCapture(String message, Set<String> agentBusinessKeywords) {
        return analyze(message, agentBusinessKeywords).capturable();
    }

    public FeedbackSignal analyze(String message, Set<String> agentBusinessKeywords) {
        String text = normalize(message);
        if (text.length() < 12) return FeedbackSignal.noise(text);
        if (TINY_OR_TEST.matcher(text).matches()) return FeedbackSignal.noise(text);
        if (!HAS_MEANINGFUL_TOKEN.matcher(text).find()) return FeedbackSignal.noise(text);

        boolean hasProblem = containsAny(text, PROBLEM_WORDS);
        boolean hasBusiness = containsAny(text, BUSINESS_WORDS);
        boolean matchesAgentBusiness = containsAny(text, agentBusinessKeywords);
        boolean hasEvidence = containsAny(text, EVIDENCE_WORDS) || text.matches(".*\\d{2,}.*");
        boolean capturable = hasProblem && (hasBusiness || hasEvidence || matchesAgentBusiness);
        return new FeedbackSignal(text, hasProblem, hasBusiness, hasEvidence, matchesAgentBusiness, false, capturable);
    }

    public String categoryOf(String message) {
        String text = normalize(message);
        if (containsAny(text, new String[]{"缺货", "补货", "空缺商品", "没货", "上架"})) return "SUPPLY_GAP";
        if (containsAny(text, new String[]{"缓存", "不一致", "对不上", "显示"})) return "DATA_INCONSISTENCY";
        if (containsAny(text, new String[]{"支付", "退款"})) return "PAYMENT";
        if (containsAny(text, new String[]{"超时", "卡", "很慢"})) return "PERFORMANCE";
        return "ISSUE_REPORT";
    }

    private static boolean containsAny(String text, String[] words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, Set<String> words) {
        if (words == null || words.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (word == null || word.isBlank()) continue;
            String normalized = word.toLowerCase(Locale.ROOT).trim();
            if (normalized.length() >= 2 && lower.contains(normalized)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    public record FeedbackSignal(String normalizedText,
                                 boolean hasProblem,
                                 boolean hasBusinessObject,
                                 boolean hasEvidence,
                                 boolean matchesAgentBusiness,
                                 boolean noise,
                                 boolean capturable) {

        static FeedbackSignal noise(String normalizedText) {
            return new FeedbackSignal(normalizedText, false, false, false, false, true, false);
        }

        public boolean likelyBusinessIssue() {
            return hasProblem && (hasBusinessObject || matchesAgentBusiness);
        }

        public boolean concreteEnough() {
            return likelyBusinessIssue() && hasEvidence;
        }
    }
}
