USE `ai-agent-station-study`;

-- V20260717 drops add_column_if_missing at the end of its migration. Keep this
-- migration independently executable for both fresh and existing databases.
SET @available_at_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'analysis_job'
    AND column_name = 'available_at'
);
SET @available_at_ddl := IF(
  @available_at_exists = 0,
  'ALTER TABLE `analysis_job` ADD COLUMN `available_at` DATETIME NULL AFTER `max_attempts`',
  'SELECT 1'
);
PREPARE available_at_stmt FROM @available_at_ddl;
EXECUTE available_at_stmt;
DEALLOCATE PREPARE available_at_stmt;

UPDATE analysis_job
SET status='COMPLETED', error_message='superseded by event-idle analysis policy', updated_at=NOW()
WHERE policy_version <> 'v3-event-idle'
  AND status IN ('PENDING','RETRY');
