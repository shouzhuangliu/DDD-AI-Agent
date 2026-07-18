USE `ai-agent-station-study`;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_column_if_missing$$
CREATE PROCEDURE add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL add_column_if_missing('ai_feedback','assistant_message_id','BIGINT UNSIGNED NULL COMMENT ''target assistant message''');
CALL add_column_if_missing('ai_feedback','feedback_type','VARCHAR(24) NOT NULL DEFAULT ''COMMENT''');
CALL add_column_if_missing('ai_feedback','rating','TINYINT NULL');
CALL add_column_if_missing('ai_feedback','correction','TEXT NULL');
CALL add_column_if_missing('ai_feedback','source_type','VARCHAR(24) NOT NULL DEFAULT ''LEGACY_AUTO_CAPTURE''');
CALL add_column_if_missing('ai_feedback','status','VARCHAR(24) NOT NULL DEFAULT ''OPEN''');
CALL add_column_if_missing('ai_feedback','submitted_by','VARCHAR(64) NOT NULL DEFAULT ''anonymous''');
CALL add_column_if_missing('ai_feedback','updated_at','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');

CALL add_column_if_missing('ai_case','agent_id','VARCHAR(32) NOT NULL DEFAULT ''''');
CALL add_column_if_missing('ai_case','summary','TEXT NULL');
CALL add_column_if_missing('ai_case','severity','VARCHAR(16) NOT NULL DEFAULT ''MEDIUM''');
CALL add_column_if_missing('ai_case','importance_score','DECIMAL(5,2) NOT NULL DEFAULT 0');
CALL add_column_if_missing('ai_case','confidence','DECIMAL(5,2) NOT NULL DEFAULT 0');
CALL add_column_if_missing('ai_case','total_score','DECIMAL(5,2) NOT NULL DEFAULT 0');
CALL add_column_if_missing('ai_case','source_model','VARCHAR(64) NOT NULL DEFAULT ''''');
CALL add_column_if_missing('ai_case','extraction_reason','TEXT NULL');
CALL add_column_if_missing('ai_case','affected_sessions','INT NOT NULL DEFAULT 0');
CALL add_column_if_missing('ai_case','last_seen_at','DATETIME NULL');
CALL add_column_if_missing('ai_case','owner','VARCHAR(64) NOT NULL DEFAULT ''''');
CALL add_column_if_missing('ai_case','resolution','TEXT NULL');

UPDATE ai_case SET status='CONFIRMED' WHERE status='active';
UPDATE ai_case SET status='ARCHIVED' WHERE status='archived';
-- This project currently has one tenant and legacy Cases were global. Attach them
-- to the default Agent so the Agent-scoped workspace does not silently hide them.
UPDATE ai_case SET agent_id='auto_agent' WHERE agent_id='';

CREATE TABLE IF NOT EXISTS `analysis_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `idempotency_key` VARCHAR(160) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL,
  `assistant_message_id` BIGINT UNSIGNED NOT NULL,
  `policy_version` VARCHAR(32) NOT NULL DEFAULT 'v1',
  `model_id` VARCHAR(64) NOT NULL DEFAULT '',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `attempts` INT NOT NULL DEFAULT 0,
  `max_attempts` INT NOT NULL DEFAULT 3,
  `lease_until` DATETIME NULL,
  `error_message` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_analysis_idempotency` (`idempotency_key`),
  KEY `idx_analysis_claim` (`status`,`lease_until`,`created_at`),
  KEY `idx_analysis_agent` (`agent_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_signal` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL,
  `assistant_message_id` BIGINT UNSIGNED NOT NULL,
  `signal_type` VARCHAR(32) NOT NULL,
  `source_type` VARCHAR(24) NOT NULL DEFAULT 'AI_INFERRED',
  `severity` VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
  `confidence` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `summary` VARCHAR(500) NOT NULL DEFAULT '',
  `rationale` TEXT NULL,
  `model_id` VARCHAR(64) NOT NULL DEFAULT '',
  `status` VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_signal_agent` (`agent_id`,`status`,`created_at`),
  KEY `idx_signal_message` (`assistant_message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `case_evidence` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `case_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `evidence_type` VARCHAR(24) NOT NULL,
  `evidence_id` BIGINT UNSIGNED NOT NULL,
  `session_id` VARCHAR(64) NOT NULL DEFAULT '',
  `message_id` BIGINT UNSIGNED NULL,
  `excerpt` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_evidence` (`case_id`,`evidence_type`,`evidence_id`),
  KEY `idx_evidence_agent` (`agent_id`,`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `case_score_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `case_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `total_score` DECIMAL(5,2) NOT NULL,
  `severity_score` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `negative_feedback_score` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `frequency_score` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `importance_score` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `recency_score` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `unresolved_age_score` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `confidence_score` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `priority_floor_applied` TINYINT NOT NULL DEFAULT 0,
  `rationale` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_score_case` (`agent_id`,`case_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `case_review_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `case_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `from_status` VARCHAR(24) NOT NULL,
  `to_status` VARCHAR(24) NOT NULL,
  `actor` VARCHAR(64) NOT NULL,
  `reason` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), KEY `idx_case_review` (`agent_id`,`case_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `memory_summary` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL, `session_id` VARCHAR(64) NOT NULL,
  `version` INT NOT NULL, `start_message_id` BIGINT UNSIGNED NOT NULL,
  `end_message_id` BIGINT UNSIGNED NOT NULL, `summary` MEDIUMTEXT NOT NULL,
  `model_id` VARCHAR(64) NOT NULL DEFAULT '', `token_count` INT NOT NULL DEFAULT 0,
  `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_memory_summary` (`session_id`,`version`),
  KEY `idx_memory_agent` (`agent_id`,`session_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `memory_state` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `agent_id` VARCHAR(32) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL, `version` INT NOT NULL,
  `goals_json` TEXT NULL, `constraints_json` TEXT NULL, `entities_json` TEXT NULL,
  `pending_json` TEXT NULL, `completed_json` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_memory_state` (`session_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `memory_tool_result` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `agent_id` VARCHAR(32) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL, `message_id` BIGINT UNSIGNED NOT NULL,
  `tool_name` VARCHAR(64) NOT NULL, `conclusion` TEXT NOT NULL,
  `key_parameters_json` TEXT NULL, `error_summary` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_memory_tool_message` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_server` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `mcp_key` VARCHAR(64) NOT NULL,
  `name` VARCHAR(160) NOT NULL, `description` TEXT NULL, `owner` VARCHAR(64) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT', `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_mcp_key` (`mcp_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `server_id` BIGINT UNSIGNED NOT NULL,
  `version` VARCHAR(32) NOT NULL, `transport_type` VARCHAR(32) NOT NULL,
  `endpoint_config` TEXT NOT NULL, `credential_ref` VARCHAR(255) NOT NULL DEFAULT '',
  `timeout_seconds` INT NOT NULL DEFAULT 60, `retry_count` INT NOT NULL DEFAULT 2,
  `concurrency_limit` INT NOT NULL DEFAULT 10, `status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  `submitted_by` VARCHAR(64) NOT NULL, `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_mcp_version` (`server_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_discovered_tool` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `tool_name` VARCHAR(128) NOT NULL, `description` TEXT NULL, `input_schema` MEDIUMTEXT NULL,
  `risk_level` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN', `enabled` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_mcp_tool` (`version_id`,`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_test_case` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(160) NOT NULL, `tool_name` VARCHAR(128) NOT NULL, `input_json` TEXT NOT NULL,
  `expected_json` TEXT NULL, `created_by` VARCHAR(64) NOT NULL, PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_test_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `test_case_id` BIGINT UNSIGNED NULL, `run_type` VARCHAR(24) NOT NULL,
  `status` VARCHAR(24) NOT NULL, `request_json` MEDIUMTEXT NULL, `response_json` MEDIUMTEXT NULL,
  `duration_ms` INT NOT NULL DEFAULT 0, `error_message` TEXT NULL,
  `executed_by` VARCHAR(64) NOT NULL, `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), KEY `idx_mcp_test_version` (`version_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_security_scan` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `status` VARCHAR(24) NOT NULL, `risk_level` VARCHAR(16) NOT NULL,
  `report_json` MEDIUMTEXT NOT NULL, `scanner_version` VARCHAR(32) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `review_type` VARCHAR(24) NOT NULL, `decision` VARCHAR(24) NOT NULL,
  `reviewer` VARCHAR(64) NOT NULL, `comment` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_review` (`version_id`,`review_type`,`reviewer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_release` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `environment` VARCHAR(24) NOT NULL, `status` VARCHAR(24) NOT NULL,
  `rollout_percent` INT NOT NULL DEFAULT 100, `released_by` VARCHAR(64) NOT NULL,
  `released_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, `ended_at` DATETIME NULL,
  PRIMARY KEY (`id`), KEY `idx_mcp_release` (`version_id`,`environment`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_mcp_release_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `agent_id` VARCHAR(32) NOT NULL,
  `release_id` BIGINT UNSIGNED NOT NULL, `enabled` TINYINT NOT NULL DEFAULT 1,
  `tool_allowlist_json` TEXT NULL, `bound_by` VARCHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_agent_mcp_release` (`agent_id`,`release_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_package` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `skill_key` VARCHAR(64) NOT NULL,
  `name` VARCHAR(160) NOT NULL, `description` TEXT NULL, `owner` VARCHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_key` (`skill_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `package_id` BIGINT UNSIGNED NOT NULL,
  `version` VARCHAR(32) NOT NULL, `status` VARCHAR(24) NOT NULL DEFAULT 'UPLOADED',
  `artifact_sha256` VARCHAR(64) NOT NULL, `manifest_json` MEDIUMTEXT NULL,
  `submitted_by` VARCHAR(64) NOT NULL, `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_skill_version` (`package_id`,`version`),
  UNIQUE KEY `uk_skill_artifact` (`artifact_sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_artifact` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `stage` VARCHAR(24) NOT NULL, `storage_path` VARCHAR(500) NOT NULL,
  `size_bytes` BIGINT NOT NULL, `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), KEY `idx_skill_artifact` (`version_id`,`stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_dependency` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `dependency_type` VARCHAR(24) NOT NULL, `dependency_key` VARCHAR(128) NOT NULL,
  `version_range` VARCHAR(64) NOT NULL DEFAULT '', `required` TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_skill_dependency` (`version_id`,`dependency_type`,`dependency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_validation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `validation_type` VARCHAR(32) NOT NULL, `status` VARCHAR(24) NOT NULL,
  `report_json` MEDIUMTEXT NOT NULL, `validator_version` VARCHAR(32) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_test_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `status` VARCHAR(24) NOT NULL, `report_json` MEDIUMTEXT NOT NULL,
  `executed_by` VARCHAR(64) NOT NULL, `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), KEY `idx_skill_test` (`version_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `review_type` VARCHAR(24) NOT NULL, `decision` VARCHAR(24) NOT NULL,
  `reviewer` VARCHAR(64) NOT NULL, `comment` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_review` (`version_id`,`review_type`,`reviewer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_release` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `version_id` BIGINT UNSIGNED NOT NULL,
  `environment` VARCHAR(24) NOT NULL, `status` VARCHAR(24) NOT NULL,
  `rollout_percent` INT NOT NULL DEFAULT 100, `signature_value` VARCHAR(128) NOT NULL,
  `released_by` VARCHAR(64) NOT NULL, `released_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ended_at` DATETIME NULL, PRIMARY KEY (`id`),
  KEY `idx_skill_release` (`version_id`,`environment`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_skill_release_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `agent_id` VARCHAR(32) NOT NULL,
  `release_id` BIGINT UNSIGNED NOT NULL, `enabled` TINYINT NOT NULL DEFAULT 1,
  `config_override_json` TEXT NULL, `bound_by` VARCHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_agent_skill_release` (`agent_id`,`release_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `resource_type` VARCHAR(32) NOT NULL,
  `resource_id` VARCHAR(128) NOT NULL, `action` VARCHAR(64) NOT NULL,
  `actor` VARCHAR(64) NOT NULL, `actor_role` VARCHAR(32) NOT NULL,
  `reason` TEXT NULL, `before_json` MEDIUMTEXT NULL, `after_json` MEDIUMTEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), KEY `idx_audit_resource` (`resource_type`,`resource_id`,`created_at`),
  KEY `idx_audit_actor` (`actor`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_missing;
