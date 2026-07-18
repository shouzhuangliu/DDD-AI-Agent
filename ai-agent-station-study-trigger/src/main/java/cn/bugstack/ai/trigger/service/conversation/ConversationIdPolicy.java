package cn.bugstack.ai.trigger.service.conversation;

import java.util.UUID;

public final class ConversationIdPolicy {

    private ConversationIdPolicy() {
    }

    public static String create() {
        return UUID.randomUUID().toString();
    }

    public static boolean isCanonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
