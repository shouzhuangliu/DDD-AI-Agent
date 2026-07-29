CREATE TABLE IF NOT EXISTS `ai_agent_tool` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '',
  `tool_id` VARCHAR(64) NOT NULL DEFAULT '',
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tool` (`agent_id`, `tool_id`),
  KEY `idx_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 绑定内置工具';
