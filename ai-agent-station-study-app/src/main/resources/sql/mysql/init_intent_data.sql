-- 新增意图分流所需配置:快速响应 client 3105
USE `ai-agent-station-study`;
SET NAMES utf8mb4;

-- 1. 快速响应的系统提示词
INSERT INTO `ai_client_system_prompt` (`prompt_id`, `prompt_name`, `prompt_content`, `description`, `status`) VALUES
('5005', '快速响应助手',
'你是一个简洁高效的对话助手。用自然、友好的中文直接回答用户问题，不要过度引申、不要罗列服务清单。简单问候就简短回应，常识问题直接给答案。\n',
'简单意图快速响应', 1)
ON DUPLICATE KEY UPDATE `prompt_content`=VALUES(`prompt_content`);

-- 2. 快速响应 client
INSERT INTO `ai_client` (`client_id`, `client_name`, `description`, `status`) VALUES
('3105', '快速响应Client', '简单意图直接回复，跳过多步链路', 1)
ON DUPLICATE KEY UPDATE `client_name`=VALUES(`client_name`);

-- 3. 关联:3105 → model 2001(DeepSeek) / prompt 5005
INSERT INTO `ai_client_config` (`source_type`, `source_id`, `target_type`, `target_id`, `ext_param`, `status`) VALUES
('client', '3105', 'model',  '2001', '', 1),
('client', '3105', 'prompt', '5005', '', 1);

-- 4. flow_config:把 3105 编排进 auto_agent,client_type=QUICK_REPLY_CLIENT(意图节点按这个取)
INSERT INTO `ai_agent_flow_config` (`agent_id`, `client_id`, `client_name`, `client_type`, `sequence`) VALUES
('auto_agent', '3105', '快速响应助手', 'QUICK_REPLY_CLIENT', 0);
