-- AI Agent 初始化业务数据(DeepSeek + auto_agent 流程)
USE `ai-agent-station-study`;

SET NAMES utf8mb4;

-- ============ 1. API 配置(DeepSeek / SenseNova)=============
INSERT INTO `ai_client_api` (`api_id`, `base_url`, `api_key`, `completions_path`, `embeddings_path`, `status`)
VALUES ('1001', 'https://api.deepseek.com', '${DEEPSEEK_API_KEY}', '/v1/chat/completions', '/v1/embeddings', 1)
ON DUPLICATE KEY UPDATE `base_url`=VALUES(`base_url`), `api_key`=VALUES(`api_key`), `completions_path`=VALUES(`completions_path`), `embeddings_path`=VALUES(`embeddings_path`), `status`=VALUES(`status`);

INSERT INTO `ai_client_api` (`api_id`, `base_url`, `api_key`, `completions_path`, `embeddings_path`, `status`)
VALUES ('1002', 'https://token.sensenova.cn', '${SENSENOVA_API_KEY}', '/v1/chat/completions', '/v1/embeddings', 1)
ON DUPLICATE KEY UPDATE `base_url`=VALUES(`base_url`), `api_key`=VALUES(`api_key`), `completions_path`=VALUES(`completions_path`), `embeddings_path`=VALUES(`embeddings_path`), `status`=VALUES(`status`);

-- ============ 2. 模型=============
INSERT INTO `ai_client_model` (`model_id`, `api_id`, `model_name`, `model_type`, `status`)
VALUES ('2001', '1001', 'deepseek-v4-flash', 'chat', 1)
ON DUPLICATE KEY UPDATE `api_id`=VALUES(`api_id`), `model_name`=VALUES(`model_name`), `model_type`=VALUES(`model_type`), `status`=VALUES(`status`);

INSERT INTO `ai_client_model` (`model_id`, `api_id`, `model_name`, `model_type`, `status`)
VALUES ('2002', '1002', 'sensenova-6.7-flash-lite', 'chat', 1)
ON DUPLICATE KEY UPDATE `api_id`=VALUES(`api_id`), `model_name`=VALUES(`model_name`), `model_type`=VALUES(`model_type`), `status`=VALUES(`status`);

-- ============ 3. 系统提示词(4 个角色)=============
INSERT INTO `ai_client_system_prompt` (`prompt_id`, `prompt_name`, `prompt_content`, `description`, `status`) VALUES
('5001', '任务分析专家',
'你是一名资深的任务分析专家。你的职责是深入理解用户需求，拆解任务目标，评估当前执行状态，并制定清晰、可落地的下一步执行策略。思考要严谨、结构化，始终以"产生实际结果"为导向。\n',
'任务分析阶段系统提示词', 1),
('5002', '精准执行专家',
'你是一名精准执行专家。你的职责是根据分析阶段制定的策略，直接产出高质量、具体可用的执行结果（如方案、计划、代码、文本等）。回答要详实、专业、可直接使用，避免空泛。\n',
'精准执行阶段系统提示词', 1),
('5003', '质量监督专家',
'你是一名严格的质量监督专家。你的职责是审视执行结果的质量、准确性、完整性和逻辑一致性，指出不足并给出具体的优化建议或修正意见。评估要客观、挑剔但有建设性。\n',
'质量监督阶段系统提示词', 1),
('5004', '智能响应助手',
'你是一名智能响应助手。你的职责是把整个执行过程的成果整理成对用户友好、清晰易懂的最终回复。用自然流畅的中文回答用户，直接给出有价值的结论与内容，不要暴露内部流程细节。\n',
'最终响应阶段系统提示词', 1)
ON DUPLICATE KEY UPDATE `prompt_content`=VALUES(`prompt_content`);

-- ============ 4. 客户端(4 个角色 client)=============
INSERT INTO `ai_client` (`client_id`, `client_name`, `description`, `status`) VALUES
('3101', '任务分析Client', 'auto_agent 任务分析节点', 1),
('3102', '精准执行Client', 'auto_agent 精准执行节点', 1),
('3103', '质量监督Client', 'auto_agent 质量监督节点', 1),
('3104', '响应助手Client', 'auto_agent 最终响应节点', 1)
ON DUPLICATE KEY UPDATE `client_name`=VALUES(`client_name`);

-- ============ 5. 关联配置(client → model / prompt)=============
-- source_type=client, target_type=model
INSERT INTO `ai_client_config` (`source_type`, `source_id`, `target_type`, `target_id`, `ext_param`, `status`) VALUES
('client', '3101', 'model',  '2001', '', 1),
('client', '3101', 'prompt', '5001', '', 1),
('client', '3102', 'model',  '2001', '', 1),
('client', '3102', 'prompt', '5002', '', 1),
('client', '3103', 'model',  '2001', '', 1),
('client', '3103', 'prompt', '5003', '', 1),
('client', '3104', 'model',  '2001', '', 1),
('client', '3104', 'prompt', '5004', '', 1);

-- ============ 6. Agent 主表 =============
INSERT INTO `ai_agent` (`agent_id`, `agent_name`, `description`, `channel`, `status`)
VALUES ('auto_agent', 'Auto智能体', '自动分析-执行-监督-响应的多步智能体', 'auto', 1)
ON DUPLICATE KEY UPDATE `agent_name`=VALUES(`agent_name`);

-- ============ 7. Agent 流程编排(4 个节点)=============
INSERT INTO `ai_agent_flow_config` (`agent_id`, `client_id`, `client_name`, `client_type`, `sequence`) VALUES
('auto_agent', '3101', '任务分析专家', 'TASK_ANALYZER_CLIENT',     1),
('auto_agent', '3102', '精准执行专家', 'PRECISION_EXECUTOR_CLIENT', 2),
('auto_agent', '3103', '质量监督专家', 'QUALITY_SUPERVISOR_CLIENT', 3),
('auto_agent', '3104', '智能响应助手', 'RESPONSE_ASSISTANT',        4);
