USE `ai-agent-station-study`;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_conversation_column_if_missing$$
CREATE PROCEDURE add_conversation_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=p_table AND column_name=p_column) THEN
    SET @ddl=CONCAT('ALTER TABLE `',p_table,'` ADD COLUMN `',p_column,'` ',p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL add_conversation_column_if_missing('ai_session','model_id','VARCHAR(64) NOT NULL DEFAULT ''''');
CALL add_conversation_column_if_missing('ai_session','last_message_at','DATETIME NULL');
CALL add_conversation_column_if_missing('ai_session','preview','VARCHAR(500) NOT NULL DEFAULT ''''');

CREATE TABLE IF NOT EXISTS `agent_soul_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(64) NOT NULL,
  `version` INT NOT NULL,
  `content` MEDIUMTEXT NOT NULL,
  `content_sha256` CHAR(64) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  `created_by` VARCHAR(64) NOT NULL DEFAULT 'local-user',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `activated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_soul_version` (`agent_id`,`version`),
  KEY `idx_agent_soul_active` (`agent_id`,`status`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS add_conversation_column_if_missing;
