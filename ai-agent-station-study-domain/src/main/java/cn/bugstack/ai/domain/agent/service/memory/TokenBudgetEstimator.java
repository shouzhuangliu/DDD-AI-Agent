package cn.bugstack.ai.domain.agent.service.memory;

/** Lightweight model-independent estimator used for triggering, never for billing. */
public class TokenBudgetEstimator {

    public int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        int cjk = 0;
        int other = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            } else if (!Character.isWhitespace(codePoint)) {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4d);
    }
}
