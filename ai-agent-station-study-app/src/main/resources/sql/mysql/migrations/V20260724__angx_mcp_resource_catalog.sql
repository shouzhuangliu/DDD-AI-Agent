-- AngX-style MCP resource model.
-- A server describes the integration, a connection stores usable credentials/config,
-- and discovered tools are cached separately. Existing ai_client_tool_mcp remains
-- available for backward compatibility.

CREATE TABLE IF NOT EXISTS `ai_mcp_server_catalog` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `server_key` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(500) DEFAULT '',
  `transport_type` VARCHAR(32) NOT NULL DEFAULT 'http',
  `base_url` VARCHAR(500) DEFAULT '',
  `command` VARCHAR(255) DEFAULT '',
  `arguments_json` TEXT,
  `auth_type` VARCHAR(32) NOT NULL DEFAULT 'none',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private',
  `health_status` VARCHAR(32) NOT NULL DEFAULT 'unknown',
  `is_verified` TINYINT NOT NULL DEFAULT 0,
  `manifest_json` TEXT,
  `tool_cache_updated_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_server_key` (`server_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务目录';

CREATE TABLE IF NOT EXISTS `ai_mcp_connection` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `server_id` BIGINT UNSIGNED NOT NULL,
  `connection_name` VARCHAR(128) NOT NULL,
  `scope_type` VARCHAR(16) NOT NULL DEFAULT 'local',
  `credential_type` VARCHAR(32) NOT NULL DEFAULT 'none',
  `credential_ref` VARCHAR(255) DEFAULT '',
  `config_json` TEXT,
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending',
  `failure_count` INT NOT NULL DEFAULT 0,
  `error_message` VARCHAR(1000) DEFAULT '',
  `last_checked_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_connection_name` (`server_id`, `connection_name`),
  KEY `idx_mcp_connection_server` (`server_id`),
  CONSTRAINT `fk_mcp_connection_server` FOREIGN KEY (`server_id`) REFERENCES `ai_mcp_server_catalog` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 可用连接与凭据引用';

CREATE TABLE IF NOT EXISTS `ai_mcp_discovered_tool` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `connection_id` BIGINT UNSIGNED NOT NULL,
  `tool_name` VARCHAR(255) NOT NULL,
  `description` TEXT,
  `input_schema_json` TEXT,
  `status` TINYINT NOT NULL DEFAULT 1,
  `discovered_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_discovered_tool` (`connection_id`, `tool_name`),
  KEY `idx_mcp_discovered_tool_connection` (`connection_id`),
  CONSTRAINT `fk_mcp_discovered_tool_connection` FOREIGN KEY (`connection_id`) REFERENCES `ai_mcp_connection` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 连接发现的工具';

CREATE TABLE IF NOT EXISTS `ai_agent_mcp_connection` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL,
  `connection_id` BIGINT UNSIGNED NOT NULL,
  `tool_allowlist_json` TEXT,
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_mcp_connection` (`agent_id`, `connection_id`),
  KEY `idx_agent_mcp_connection_agent` (`agent_id`),
  CONSTRAINT `fk_agent_mcp_connection` FOREIGN KEY (`connection_id`) REFERENCES `ai_mcp_connection` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 绑定的 MCP 连接';
