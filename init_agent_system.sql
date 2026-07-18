-- 专属 Agent 系统：AITER + 绑定表
USE `ai-agent-station-study`;
SET NAMES utf8mb4;

-- 1. ai_agent 加列（MySQL 兼容的幂等迁移）
SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'system_prompt') = 0,
  'ALTER TABLE `ai_agent` ADD COLUMN `system_prompt` TEXT COMMENT ''灵魂：system prompt（专属Agent人格/角色）'' AFTER `description`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'model_id') = 0,
  'ALTER TABLE `ai_agent` ADD COLUMN `model_id` VARCHAR(32) DEFAULT ''2001'' COMMENT ''绑定的模型 id'' AFTER `system_prompt`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'work_dir') = 0,
  'ALTER TABLE `ai_agent` ADD COLUMN `work_dir` VARCHAR(255) DEFAULT '''' COMMENT ''工具沙箱工作目录(空=用默认)'' AFTER `model_id`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 绑定表
CREATE TABLE IF NOT EXISTS `ai_agent_skill` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL,
  `skill_id` VARCHAR(64) NOT NULL,
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skill` (`agent_id`, `skill_id`),
  KEY `idx_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 技能绑定表';

CREATE TABLE IF NOT EXISTS `ai_agent_mcp` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL,
  `mcp_id` VARCHAR(32) NOT NULL,
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_mcp` (`agent_id`, `mcp_id`),
  KEY `idx_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent MCP 工具绑定表';
