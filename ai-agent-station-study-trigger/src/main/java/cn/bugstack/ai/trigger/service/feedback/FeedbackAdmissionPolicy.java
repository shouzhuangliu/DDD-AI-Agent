package cn.bugstack.ai.trigger.service.feedback;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 自动 Feedback 采集准入策略。
 * <p>
 * 仪表盘追求“少而准”，所以自动采集宁可漏掉模糊输入，
 * 也不能把测试输入、问候语或无业务对象的噪声沉淀成真实业务反馈。
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

    public boolean shouldCapture(String message) {
        String text = normalize(message);
        if (text.length() < 12) return false;
        if (TINY_OR_TEST.matcher(text).matches()) return false;
        if (!HAS_MEANINGFUL_TOKEN.matcher(text).find()) return false;
        return containsAny(text, PROBLEM_WORDS) && (containsAny(text, BUSINESS_WORDS) || text.length() >= 24);
    }

    private static boolean containsAny(String text, String[] words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }
}
