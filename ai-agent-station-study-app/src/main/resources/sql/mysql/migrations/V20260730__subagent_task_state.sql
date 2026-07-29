USE `ai-agent-station-study`;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `subagent_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id` VARCHAR(64) NOT NULL,
  `execution_id` VARCHAR(64) NOT NULL DEFAULT '',
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '',
  `description` VARCHAR(255) NOT NULL DEFAULT '',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `result_text` MEDIUMTEXT NULL,
  `error_message` TEXT NULL,
  `cancel_requested` TINYINT NOT NULL DEFAULT 0,
  `started_at` DATETIME NULL,
  `completed_at` DATETIME NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subagent_task_id` (`task_id`),
  KEY `idx_subagent_task_execution` (`execution_id`,`updated_at`),
  KEY `idx_subagent_task_agent` (`agent_id`,`updated_at`),
  KEY `idx_subagent_task_status` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
