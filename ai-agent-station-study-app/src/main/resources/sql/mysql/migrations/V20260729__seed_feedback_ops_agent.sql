USE `ai-agent-station-study`;
SET NAMES utf8mb4;

-- 反馈运维 Agent：不绑定固定模型，模型沿用对话页的当前选择。
INSERT INTO `ai_agent`
  (`agent_id`, `agent_name`, `description`, `system_prompt`, `model_id`, `work_dir`, `channel`, `status`)
VALUES
  ('feedback-ops-agent', '反馈运维助手', '收集用户和运维反馈，按阶段升级为 Case 并沉淀 Agent 画像。',
   '你是企业反馈运维助手。先保留原始事实，再逐步分类、收集证据和请求人工审核。每次只读取当前阶段 Skill，不越权修改生产系统。回复使用中文，明确当前阶段、事实、下一步和人工确认项。',
   NULL, 'feedback-ops-agent-sandbox', 'feedback-ops', 1)
ON DUPLICATE KEY UPDATE
  `agent_name`=VALUES(`agent_name`), `description`=VALUES(`description`),
  `system_prompt`=VALUES(`system_prompt`), `work_dir`=VALUES(`work_dir`),
  `channel`=VALUES(`channel`), `status`=1;

INSERT INTO `ai_agent_skill` (`agent_id`, `skill_id`, `status`)
VALUES
  ('feedback-ops-agent', 'feedback-ops-agent', 1)
ON DUPLICATE KEY UPDATE `status`=1;

INSERT INTO `mcp_server` (`mcp_key`, `name`, `description`, `owner`, `status`)
VALUES ('feedback-ops-mcp', '反馈运维 MCP', '反馈、Case 证据和运维只读诊断工具。', 'local-system', 'ACTIVE')
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`), `description`=VALUES(`description`), `status`='ACTIVE';

SET @feedback_mcp_server_id = (SELECT `id` FROM `mcp_server` WHERE `mcp_key`='feedback-ops-mcp' LIMIT 1);
INSERT INTO `mcp_version`
  (`server_id`, `version`, `transport_type`, `endpoint_config`, `credential_ref`, `status`, `submitted_by`)
VALUES
  (@feedback_mcp_server_id, '1.0.0', 'stdio',
   '{"command":"python","args":["mcp-test-server/feedback_ops_mcp.py"],"workingDirectory":"."}',
   '', 'RELEASED', 'local-system')
ON DUPLICATE KEY UPDATE `endpoint_config`=VALUES(`endpoint_config`), `status`='RELEASED';
SET @feedback_mcp_version_id = (SELECT `id` FROM `mcp_version` WHERE `server_id`=@feedback_mcp_server_id AND `version`='1.0.0' LIMIT 1);

INSERT INTO `mcp_discovered_tool` (`version_id`, `tool_name`, `description`, `input_schema`, `risk_level`, `enabled`) VALUES
  (@feedback_mcp_version_id, 'create_feedback', '保存用户或运维反馈。', '{"type":"object"}', 'MEDIUM', 1),
  (@feedback_mcp_version_id, 'search_feedback', '查询反馈。', '{"type":"object"}', 'LOW', 1),
  (@feedback_mcp_version_id, 'get_feedback_detail', '读取反馈详情。', '{"type":"object"}', 'LOW', 1),
  (@feedback_mcp_version_id, 'promote_feedback_to_case', '人工确认后升级 Case。', '{"type":"object"}', 'MEDIUM', 1),
  (@feedback_mcp_version_id, 'append_case_evidence', '追加 Case 证据。', '{"type":"object"}', 'MEDIUM', 1),
  (@feedback_mcp_version_id, 'get_case_timeline', '读取 Case 时间线。', '{"type":"object"}', 'LOW', 1),
  (@feedback_mcp_version_id, 'search_incidents', '查询运维事件。', '{"type":"object"}', 'LOW', 1),
  (@feedback_mcp_version_id, 'get_service_health', '读取服务健康快照。', '{"type":"object"}', 'LOW', 1)
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`), `enabled`=1;

INSERT INTO `mcp_release` (`version_id`, `environment`, `status`, `rollout_percent`, `released_by`)
VALUES (@feedback_mcp_version_id, 'LOCAL', 'ACTIVE', 100, 'local-system');
SET @feedback_mcp_release_id = (SELECT MAX(`id`) FROM `mcp_release` WHERE `version_id`=@feedback_mcp_version_id AND `status`='ACTIVE');

INSERT INTO `ai_client_tool_mcp`
  (`mcp_id`, `mcp_name`, `transport_type`, `transport_config`, `request_timeout`, `status`, `create_time`, `update_time`)
VALUES ('feedback-ops-mcp', '反馈运维 MCP', 'stdio', '{"command":"python","args":["mcp-test-server/feedback_ops_mcp.py"],"workingDirectory":"."}', 60, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE `transport_config`=VALUES(`transport_config`), `status`=1, `update_time`=NOW();

INSERT INTO `ai_agent_mcp` (`agent_id`, `mcp_id`, `status`)
VALUES ('feedback-ops-agent', 'feedback-ops-mcp', 1)
ON DUPLICATE KEY UPDATE `status`=1;
INSERT INTO `agent_mcp_release_binding` (`agent_id`, `release_id`, `enabled`, `tool_allowlist_json`, `bound_by`)
VALUES ('feedback-ops-agent', @feedback_mcp_release_id, 1, '[]', 'local-system')
ON DUPLICATE KEY UPDATE `enabled`=1, `tool_allowlist_json`='[]';
