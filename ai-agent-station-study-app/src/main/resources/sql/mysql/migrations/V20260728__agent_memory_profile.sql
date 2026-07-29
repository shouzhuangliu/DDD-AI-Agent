USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `agent_memory_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(64) NOT NULL,
  `version` INT NOT NULL,
  `profile_json` MEDIUMTEXT NOT NULL,
  `source_case_ids` TEXT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_memory_profile_version` (`agent_id`,`version`),
  KEY `idx_agent_memory_profile_latest` (`agent_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
