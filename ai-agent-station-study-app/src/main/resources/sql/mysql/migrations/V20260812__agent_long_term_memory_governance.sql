USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `agent_memory_candidate` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `candidate_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(64) NOT NULL,
  `memory_type` VARCHAR(32) NOT NULL,
  `memory_key` VARCHAR(191) NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `summary` TEXT NOT NULL,
  `content_json` JSON NOT NULL,
  `source_type` VARCHAR(24) NOT NULL,
  `source_id` VARCHAR(128) NOT NULL,
  `source_session_id` VARCHAR(64) NOT NULL DEFAULT '',
  `source_case_id` VARCHAR(64) NOT NULL DEFAULT '',
  `confidence` INT NOT NULL DEFAULT 0,
  `status` VARCHAR(24) NOT NULL DEFAULT 'EXTRACTED',
  `extraction_model_id` VARCHAR(64) NOT NULL DEFAULT '',
  `prompt_version` VARCHAR(32) NOT NULL DEFAULT '',
  `reviewed_by` VARCHAR(64) NOT NULL DEFAULT '',
  `reviewed_at` DATETIME NULL,
  `review_comment` VARCHAR(1000) NOT NULL DEFAULT '',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_candidate_id` (`candidate_id`),
  UNIQUE KEY `uk_memory_candidate_source` (`agent_id`,`memory_type`,`memory_key`,`source_type`,`source_id`),
  KEY `idx_memory_candidate_review` (`agent_id`,`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_memory_evidence` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memory_owner_type` VARCHAR(16) NOT NULL,
  `memory_owner_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(64) NOT NULL,
  `source_type` VARCHAR(24) NOT NULL,
  `source_id` VARCHAR(128) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL DEFAULT '',
  `message_id` BIGINT UNSIGNED NULL,
  `tool_call_id` VARCHAR(128) NOT NULL DEFAULT '',
  `evidence_quote` TEXT NOT NULL,
  `content_hash` CHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_evidence_source` (`memory_owner_type`,`memory_owner_id`,`source_type`,`source_id`),
  KEY `idx_memory_evidence_agent` (`agent_id`,`memory_owner_type`,`memory_owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_memory_card` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memory_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(64) NOT NULL,
  `memory_type` VARCHAR(32) NOT NULL,
  `memory_key` VARCHAR(191) NOT NULL,
  `version` INT NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `description` VARCHAR(1000) NOT NULL DEFAULT '',
  `content_json` JSON NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
  `source_candidate_id` VARCHAR(64) NOT NULL,
  `source_case_id` VARCHAR(64) NOT NULL DEFAULT '',
  `effective_at` DATETIME NOT NULL,
  `expires_at` DATETIME NULL,
  `published_by` VARCHAR(64) NOT NULL,
  `published_at` DATETIME NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_card_version` (`agent_id`,`memory_id`,`version`),
  KEY `idx_memory_card_key` (`agent_id`,`memory_key`,`version`),
  KEY `idx_memory_card_lookup` (`agent_id`,`status`,`memory_type`,`effective_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_memory_extraction_cursor` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(64) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL,
  `last_message_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  `last_status` VARCHAR(24) NOT NULL DEFAULT 'IDLE',
  `retry_count` INT NOT NULL DEFAULT 0,
  `last_error` VARCHAR(2000) NOT NULL DEFAULT '',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_cursor` (`agent_id`,`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_memory_index_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(64) NOT NULL,
  `memory_id` VARCHAR(64) NOT NULL,
  `memory_version` INT NOT NULL,
  `event_type` VARCHAR(16) NOT NULL,
  `payload_json` JSON NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `attempts` INT NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` VARCHAR(2000) NOT NULL DEFAULT '',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_outbox_event` (`event_id`),
  KEY `idx_memory_outbox_claim` (`status`,`next_retry_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
