-- Runtime failures are observability records, not business feedback evidence.
UPDATE ai_signal
SET source_type = 'RUNTIME_OBSERVATION'
WHERE signal_type IN ('TOOL_FAILURE', 'MCP_FAILURE', 'MODEL_FAILURE',
                      'MODEL_RATE_LIMIT', 'EXECUTION_FAILURE')
  AND (source_type IS NULL OR source_type = 'AI_INFERRED');
