-- Repair the legacy default model which was accidentally paired with the
-- SenseNova endpoint while carrying a DeepSeek model name.
UPDATE `ai_client_model`
SET `api_id` = '1001', `model_name` = 'deepseek-chat', `model_type` = 'openai'
WHERE `model_id` = '2001'
  AND (`api_id` = 'api-2001' OR `model_name` = 'deepseek-v4-flash');
