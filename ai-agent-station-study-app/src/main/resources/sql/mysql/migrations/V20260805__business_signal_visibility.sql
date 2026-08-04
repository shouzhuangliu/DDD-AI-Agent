-- 普通查询/闲聊被评为 NOT_ELIGIBLE 时，不应继续出现在业务信号面板。
-- 运行时错误另由 ai_signal.source_type=RUNTIME_OBSERVATION 标记，前端业务接口会过滤。
UPDATE ai_signal s
JOIN case_evaluation_snapshot e
  ON e.agent_id = s.agent_id
 AND e.session_id = s.session_id
 AND e.assistant_message_id = s.assistant_message_id
SET s.status = 'SUPPRESSED'
WHERE e.decision = 'NOT_ELIGIBLE'
  AND s.status IN ('OPEN', 'OBSERVED');

UPDATE ai_signal
SET source_type = 'RUNTIME_OBSERVATION'
WHERE signal_type IN ('TOOL_FAILURE', 'MCP_FAILURE', 'MODEL_FAILURE',
                      'MODEL_RATE_LIMIT', 'EXECUTION_FAILURE')
  AND (source_type IS NULL OR source_type = 'AI_INFERRED');
