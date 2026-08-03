CALL add_column_if_missing('analysis_job','available_at','DATETIME NULL');

UPDATE analysis_job
SET status='COMPLETED', error_message='superseded by event-idle analysis policy', updated_at=NOW()
WHERE policy_version <> 'v3-event-idle'
  AND status IN ('PENDING','RETRY');
