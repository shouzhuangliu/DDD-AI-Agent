USE `ai-agent-station-study`;

ALTER TABLE `agent_memory_card`
    ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记',
    ADD COLUMN `importance` INT NOT NULL DEFAULT 50 COMMENT '重要度 0-100',
    ADD COLUMN `pinned` TINYINT NOT NULL DEFAULT 0 COMMENT '是否首轮固定注入',
    ADD COLUMN `updated_reason` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '最近变更原因';

CREATE TABLE IF NOT EXISTS `agent_memory_change_log` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `change_id` VARCHAR(64) NOT NULL,
    `agent_id` VARCHAR(64) NOT NULL,
    `memory_id` VARCHAR(64) NOT NULL,
    `memory_version` INT NOT NULL,
    `operation` VARCHAR(16) NOT NULL COMMENT 'CREATE/UPDATE/RETIRE',
    `reason` VARCHAR(1000) NOT NULL,
    `source_type` VARCHAR(24) NOT NULL,
    `source_id` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_change_id` (`change_id`),
    KEY `idx_memory_change_agent` (`agent_id`, `memory_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
