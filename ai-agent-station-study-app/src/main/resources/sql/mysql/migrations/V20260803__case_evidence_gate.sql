-- Case 证据门禁迁移：保存一次评测的不可变快照与证据引用。
-- 该脚本可重复执行，适用于已有本地库和全新数据库。
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `case_evidence` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `case_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `evidence_type` VARCHAR(24) NOT NULL,
  `evidence_id` BIGINT UNSIGNED NOT NULL,
  `session_id` VARCHAR(64) NOT NULL DEFAULT '',
  `message_id` BIGINT UNSIGNED NULL,
  `excerpt` TEXT NULL,
  `evidence_role` VARCHAR(16) NOT NULL DEFAULT '',
  `skill_rule_id` VARCHAR(128) NOT NULL DEFAULT '',
  `supports_json` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_evidence` (`case_id`,`evidence_type`,`evidence_id`),
  KEY `idx_evidence_agent` (`agent_id`,`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Case 原始证据引用';

CREATE TABLE IF NOT EXISTS `case_evaluation_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `idempotency_key` VARCHAR(180) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL,
  `assistant_message_id` BIGINT UNSIGNED NOT NULL,
  `policy_version` VARCHAR(32) NOT NULL DEFAULT 'v4-evidence-gate',
  `decision` VARCHAR(32) NOT NULL,
  `skill_id` VARCHAR(64) NOT NULL DEFAULT '',
  `rule_ids_json` TEXT NULL,
  `facts_json` TEXT NULL,
  `missing_information_json` TEXT NULL,
  `evidence_json` TEXT NULL,
  `confidence` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `server_score` INT NOT NULL DEFAULT 0,
  `reason` TEXT NULL,
  `evidence_fingerprint` VARCHAR(64) NOT NULL DEFAULT '',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_evaluation_idempotency` (`idempotency_key`),
  KEY `idx_case_evaluation_agent` (`agent_id`,`created_at`),
  KEY `idx_case_evaluation_session` (`agent_id`,`session_id`,`created_at`),
  KEY `idx_case_evaluation_fingerprint` (`agent_id`,`session_id`,`evidence_fingerprint`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Case 结构化评测不可变快照';

SET @db_name = DATABASE();

UPDATE analysis_job
SET status='COMPLETED', error_message='superseded by v4-evidence-gate', updated_at=NOW()
WHERE policy_version <> 'v4-evidence-gate'
  AND status IN ('PENDING','RETRY','RUNNING');

SET @sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=@db_name AND table_name='case_evidence')
  AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=@db_name AND table_name='case_evidence' AND column_name='evidence_role'),
  'ALTER TABLE case_evidence ADD COLUMN evidence_role VARCHAR(16) NOT NULL DEFAULT '''' AFTER excerpt',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=@db_name AND table_name='case_evidence')
  AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=@db_name AND table_name='case_evidence' AND column_name='skill_rule_id'),
  'ALTER TABLE case_evidence ADD COLUMN skill_rule_id VARCHAR(128) NOT NULL DEFAULT '''' AFTER evidence_role',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=@db_name AND table_name='case_evidence')
  AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=@db_name AND table_name='case_evidence' AND column_name='supports_json'),
  'ALTER TABLE case_evidence ADD COLUMN supports_json TEXT NULL AFTER skill_rule_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT 'OK' AS result;
