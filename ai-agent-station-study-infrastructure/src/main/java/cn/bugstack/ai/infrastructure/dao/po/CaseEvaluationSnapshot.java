package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 一次 Case 评测的不可变审计快照。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseEvaluationSnapshot {
    private Long id;
    private String idempotencyKey;
    private String agentId;
    private String sessionId;
    private Long assistantMessageId;
    private String policyVersion;
    private String decision;
    private String skillId;
    private String ruleIdsJson;
    private String factsJson;
    private String missingInformationJson;
    private String evidenceJson;
    private Double confidence;
    private Integer serverScore;
    private String reason;
    private String evidenceFingerprint;
    private LocalDateTime createdAt;
}
