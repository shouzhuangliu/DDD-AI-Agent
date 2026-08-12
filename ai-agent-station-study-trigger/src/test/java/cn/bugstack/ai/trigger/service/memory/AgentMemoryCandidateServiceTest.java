package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.MemoryPublicationPolicy;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentMemoryCandidateServiceTest {

    private final IAgentMemoryCandidateDao candidateDao = mock(IAgentMemoryCandidateDao.class);
    private final IAgentMemoryEvidenceDao evidenceDao = mock(IAgentMemoryEvidenceDao.class);
    private final IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
    private final IAgentMemoryIndexOutboxDao outboxDao = mock(IAgentMemoryIndexOutboxDao.class);
    private final IAiCaseDao caseDao = mock(IAiCaseDao.class);
    private final IChatMessageDao messageDao = mock(IChatMessageDao.class);
    private final AgentMemoryCandidateService service = new AgentMemoryCandidateService(
            candidateDao, evidenceDao, cardDao, outboxDao, caseDao, messageDao);

    @Test
    void unresolvedCaseCannotBeApprovedForPublication() {
        AgentMemoryCandidate candidate = resolvedCaseCandidate("APPROVED");
        when(candidateDao.queryByCandidateId("inventory", "candidate-1")).thenReturn(candidate);
        when(caseDao.queryByAgentAndCaseId("inventory", "case-1"))
                .thenReturn(AiCase.builder().agentId("inventory").caseId("case-1").status("PROCESSING").build());

        assertThrows(IllegalStateException.class,
                () -> service.publish("inventory", "candidate-1", "developer"));
        verifyNoInteractions(cardDao, outboxDao);
    }

    @Test
    void approvedResolvedCasePublishesCardAndOutbox() {
        AgentMemoryCandidate candidate = resolvedCaseCandidate("APPROVED");
        when(candidateDao.queryByCandidateId("inventory", "candidate-1")).thenReturn(candidate);
        when(caseDao.queryByAgentAndCaseId("inventory", "case-1"))
                .thenReturn(AiCase.builder().agentId("inventory").caseId("case-1").status("RESOLVED")
                        .summary("库存不一致已解决").build());
        when(evidenceDao.queryByOwner("inventory", "CANDIDATE", "candidate-1"))
                .thenReturn(List.of(AgentMemoryEvidence.builder().agentId("inventory")
                        .memoryOwnerType("CANDIDATE").memoryOwnerId("candidate-1")
                        .sourceType("CASE").sourceId("case-1").evidenceQuote("库存不一致已解决")
                        .contentHash(hash("\n库存不一致已解决\n")).build()));
        when(candidateDao.transition("inventory", "candidate-1", "APPROVED", "PUBLISHED",
                "developer", "发布长期记忆", null)).thenReturn(1);

        AgentMemoryCandidateService.PublishedMemory result =
                service.publish("inventory", "candidate-1", "developer");

        assertEquals("PUBLISHED", result.status());
        verify(cardDao).insert(any(AgentMemoryCard.class));
        verify(outboxDao).insert(any(AgentMemoryIndexOutbox.class));
    }

    @Test
    void evidenceFromAnotherAgentIsRejected() {
        ChatMessage foreign = ChatMessage.builder().id(18L).agentId("ops").sessionId("s-ops")
                .role("user").content("其它 Agent 的内容").build();
        when(messageDao.queryById(18L)).thenReturn(foreign);

        AgentMemoryCandidateService.SubmitCandidate request = new AgentMemoryCandidateService.SubmitCandidate(
                "inventory", "BUSINESS_RULE", "inventory:rule", "库存规则", "稳定库存规则", "{}",
                "SESSION", "s-1:18", "s-1", "", 90, "m1", "v1",
                List.of(new AgentMemoryCandidateService.EvidenceInput("MESSAGE", "18", "s-1", 18L, "", "其它 Agent 的内容")));

        assertThrows(IllegalArgumentException.class, () -> service.submitCandidate(request));
        verify(candidateDao, never()).insertIgnore(any());
    }

    @Test
    void illegalStateTransitionIsRejected() {
        MemoryPublicationPolicy policy = new MemoryPublicationPolicy();
        assertThrows(IllegalStateException.class,
                () -> policy.requireTransition(MemoryPublicationPolicy.CandidateStatus.EXTRACTED,
                        MemoryPublicationPolicy.CandidateStatus.PUBLISHED));
    }

    @Test
    void duplicateSourceReturnsExistingCandidateWithoutOrphanEvidence() {
        ChatMessage message = ChatMessage.builder().id(18L).agentId("inventory").sessionId("s-1")
                .role("user").content("DDR5 库存不足").build();
        when(messageDao.queryById(18L)).thenReturn(message);
        when(candidateDao.insertIgnore(any())).thenReturn(0);
        when(candidateDao.queryByUniqueSource("inventory", "BUSINESS_RULE", "inventory:rule",
                "SESSION", "s-1:18")).thenReturn(AgentMemoryCandidate.builder().candidateId("existing-1").build());
        AgentMemoryCandidateService.SubmitCandidate request = new AgentMemoryCandidateService.SubmitCandidate(
                "inventory", "BUSINESS_RULE", "inventory:rule", "库存规则", "DDR5 库存不足", "{}",
                "SESSION", "s-1:18", "s-1", "", 90, "m1", "v1",
                List.of(new AgentMemoryCandidateService.EvidenceInput("MESSAGE", "18", "s-1", 18L, "", "DDR5 库存不足")));

        assertEquals("existing-1", service.submitCandidate(request));
        verifyNoInteractions(evidenceDao);
    }

    private AgentMemoryCandidate resolvedCaseCandidate(String status) {
        return AgentMemoryCandidate.builder().candidateId("candidate-1").agentId("inventory")
                .memoryType("RESOLVED_CASE").memoryKey("inventory:case-1").title("库存不一致")
                .summary("库存不一致已解决").contentJson("{\"resolution\":\"重建库存\"}")
                .sourceType("CASE").sourceId("case-1").sourceCaseId("case-1")
                .status(status).confidence(95).build();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
