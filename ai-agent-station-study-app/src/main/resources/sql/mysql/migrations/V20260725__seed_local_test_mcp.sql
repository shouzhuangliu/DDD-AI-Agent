-- Register the dependency-free local MCP so it is visible in the console.
-- The row is intentionally left DRAFT: the user can run the normal lifecycle
-- and decide when it becomes selectable by an Agent.

INSERT INTO `mcp_server` (`mcp_key`, `name`, `description`, `owner`, `status`)
VALUES ('local-test-mcp', '本地测试 MCP', '用于测试 echo、add、current_time 工具的本地 STDIO MCP', 'local-system', 'DRAFT')
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`),
  `description` = VALUES(`description`);

SET @local_test_mcp_server_id = (
  SELECT `id` FROM `mcp_server` WHERE `mcp_key` = 'local-test-mcp' LIMIT 1
);

INSERT INTO `mcp_version`
  (`server_id`, `version`, `transport_type`, `endpoint_config`, `credential_ref`, `submitted_by`)
VALUES
  (@local_test_mcp_server_id, '1.0.0', 'stdio',
   '{"command":"python","args":["D:/javacode/ai-agent/ai-agent-station-study/mcp-test-server/test_mcp_server.py"]}',
   '', 'local-system')
ON DUPLICATE KEY UPDATE
  `endpoint_config` = VALUES(`endpoint_config`),
  `transport_type` = VALUES(`transport_type`);

SET @local_test_mcp_version_id = (
  SELECT `id` FROM `mcp_version`
  WHERE `server_id` = @local_test_mcp_server_id AND `version` = '1.0.0' LIMIT 1
);

INSERT INTO `mcp_discovered_tool`
  (`version_id`, `tool_name`, `description`, `input_schema`, `risk_level`, `enabled`)
VALUES
  (@local_test_mcp_version_id, 'echo', 'Return the supplied text.', '{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}', 'LOW', 1),
  (@local_test_mcp_version_id, 'add', 'Add two numbers.', '{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}', 'LOW', 1),
  (@local_test_mcp_version_id, 'current_time', 'Return the current UTC time.', '{"type":"object","properties":{}}', 'LOW', 1)
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`),
  `input_schema` = VALUES(`input_schema`),
  `enabled` = 1;

INSERT INTO `ai_mcp_server_catalog`
  (`server_key`, `name`, `description`, `transport_type`, `command`, `auth_type`, `visibility`)
VALUES
  ('local-test-mcp', '本地测试 MCP', '本地 STDIO 测试 MCP', 'stdio', 'python', 'none', 'private')
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`),
  `description` = VALUES(`description`);

SET @local_test_catalog_id = (
  SELECT `id` FROM `ai_mcp_server_catalog` WHERE `server_key` = 'local-test-mcp' LIMIT 1
);

INSERT INTO `ai_mcp_connection`
  (`server_id`, `connection_name`, `scope_type`, `credential_type`, `credential_ref`, `config_json`, `status`)
VALUES
  (@local_test_catalog_id, '本地默认连接', 'local', 'none', '',
   '{"command":"python","args":["D:/javacode/ai-agent/ai-agent-station-study/mcp-test-server/test_mcp_server.py"]}', 'pending')
ON DUPLICATE KEY UPDATE
  `config_json` = VALUES(`config_json`),
  `status` = 'pending';
