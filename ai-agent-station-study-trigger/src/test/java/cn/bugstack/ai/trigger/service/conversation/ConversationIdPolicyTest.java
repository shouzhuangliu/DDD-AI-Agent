package cn.bugstack.ai.trigger.service.conversation;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationIdPolicyTest {

    @Test
    void createsCanonicalUuidForEveryNewConversation() {
        String sessionId = ConversationIdPolicy.create();

        assertEquals(sessionId, UUID.fromString(sessionId).toString());
        assertTrue(ConversationIdPolicy.isCanonicalUuid(sessionId));
    }

    @Test
    void rejectsLegacyTimestampAndMalformedIdsAsNewConversationIds() {
        assertFalse(ConversationIdPolicy.isCanonicalUuid("sess-1784288038674"));
        assertFalse(ConversationIdPolicy.isCanonicalUuid("not-a-uuid"));
    }
}
