USE `ai-agent-station-study`;

-- V20260806 briefly treated MCP as a Case prerequisite. Restore records that
-- still have a valid active Skill binding; MCP is optional for business Cases.
UPDATE ai_case c
JOIN ai_agent_skill s
  ON s.agent_id = c.agent_id
 AND s.skill_id = c.skill_id
 AND s.status = 1
SET c.status = 'CANDIDATE',
    c.resolution = '',
    c.extraction_reason = CONCAT(
      '业务边界修复：MCP 不是 Case 必选条件；原始原因：',
      COALESCE(c.extraction_reason, '')
    ),
    c.updated_at = NOW()
WHERE c.status = 'ARCHIVED'
  AND c.extraction_reason LIKE '业务边界迁移：缺少当前 Agent 的有效 Skill/MCP 绑定%';

-- Older evaluators sometimes emitted OTHER for an MCP/tool failure. Keep the
-- trace, but move it out of the business-signal stream.
UPDATE ai_signal
SET source_type = 'RUNTIME_OBSERVATION',
    signal_type = 'MCP_FAILURE',
    rationale = CONCAT('运行时 MCP/工具异常：', COALESCE(rationale, ''))
WHERE source_type = 'AI_INFERRED'
  AND signal_type NOT IN ('TOOL_FAILURE','MCP_FAILURE','MODEL_FAILURE','MODEL_RATE_LIMIT','EXECUTION_FAILURE')
  AND (
    LOWER(COALESCE(summary, '')) LIKE '%timeout%'
    OR LOWER(COALESCE(rationale, '')) LIKE '%timeout%'
    OR LOWER(COALESCE(summary, '')) LIKE '%connection refused%'
    OR LOWER(COALESCE(rationale, '')) LIKE '%connection refused%'
    OR summary LIKE '%工具执行失败%'
    OR rationale LIKE '%工具执行失败%'
    OR summary LIKE '%工具调用已拦截%'
    OR rationale LIKE '%工具调用已拦截%'
    OR summary LIKE '%MCP 调用异常%'
    OR rationale LIKE '%MCP 调用异常%'
    OR summary LIKE '%连接超时%'
    OR rationale LIKE '%连接超时%'
    OR summary LIKE '%连接失败%'
    OR rationale LIKE '%连接失败%'
    OR summary LIKE '%调用失败%'
    OR rationale LIKE '%调用失败%'
    OR (
      (LOWER(COALESCE(summary, '')) LIKE '%mcp%' OR LOWER(COALESCE(rationale, '')) LIKE '%mcp%')
      AND (
        summary LIKE '%失败%' OR rationale LIKE '%失败%'
        OR summary LIKE '%异常%' OR rationale LIKE '%异常%'
        OR summary LIKE '%错误%' OR rationale LIKE '%错误%'
        OR summary LIKE '%拒绝%' OR rationale LIKE '%拒绝%'
        OR summary LIKE '%不可用%' OR rationale LIKE '%不可用%'
        OR LOWER(COALESCE(summary, '')) LIKE '%error%'
        OR LOWER(COALESCE(rationale, '')) LIKE '%error%'
        OR LOWER(COALESCE(summary, '')) LIKE '%failed%'
        OR LOWER(COALESCE(rationale, '')) LIKE '%failed%'
      )
    )
  );
