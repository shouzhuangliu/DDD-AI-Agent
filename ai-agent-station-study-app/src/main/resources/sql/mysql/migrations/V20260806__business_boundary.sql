USE `ai-agent-station-study`;

-- Business Feedback is only valid for an Agent with an enabled Skill.
UPDATE ai_feedback f
LEFT JOIN ai_agent_skill s
  ON s.agent_id = f.agent_id
 AND s.status = 1
SET f.status = 'INVALID',
    f.resolved = 1,
    f.category = 'UNBOUND_AGENT',
    f.updated_at = NOW()
WHERE f.status IN ('OPEN','NEED_MORE_INFO','VALID','CLUSTERED')
  AND s.agent_id IS NULL;

-- A Case must point to the currently bound Skill and have an enabled MCP
-- binding. Historical records that cannot satisfy that boundary are retained
-- for audit but removed from active business operations.
UPDATE ai_case c
LEFT JOIN ai_agent_skill s
  ON s.agent_id = c.agent_id
 AND s.skill_id = c.skill_id
 AND s.status = 1
LEFT JOIN ai_agent_mcp m
  ON m.agent_id = c.agent_id
 AND m.status = 1
SET c.status = 'ARCHIVED',
    c.resolution = CASE
      WHEN c.resolution IS NULL OR TRIM(c.resolution) = ''
        THEN '历史记录缺少绑定 Skill 或 MCP 业务证据，已隔离等待重新评测'
      ELSE c.resolution
    END,
    c.extraction_reason = CONCAT(
      '业务边界迁移：缺少当前 Agent 的有效 Skill/MCP 绑定；原始原因：',
      COALESCE(c.extraction_reason, '')
    ),
    c.updated_at = NOW()
WHERE c.status IN ('CANDIDATE','PENDING_REVIEW','CONFIRMED','IN_PROGRESS')
  AND (NULLIF(TRIM(c.skill_id), '') IS NULL OR s.agent_id IS NULL OR m.agent_id IS NULL);

-- AI-inferred business signals from unbound Agents must not appear in the
-- business dashboard. Runtime observations remain available to tracing.
UPDATE ai_signal x
LEFT JOIN ai_agent_skill s
  ON s.agent_id = x.agent_id
 AND s.status = 1
SET x.status = 'SUPPRESSED'
WHERE x.source_type = 'AI_INFERRED'
  AND s.agent_id IS NULL
  AND x.status IN ('OPEN','OBSERVED');
