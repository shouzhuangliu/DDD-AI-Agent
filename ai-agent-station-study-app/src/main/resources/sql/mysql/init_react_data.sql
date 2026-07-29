-- ReAct 智能体模式初始化配置
USE `ai-agent-station-study`;
SET NAMES utf8mb4;

-- 1. ReAct 系统提示词（让模型知道有工具可用，足够引导其自主决策）
INSERT INTO `ai_client_system_prompt` (`prompt_id`, `prompt_name`, `prompt_content`, `description`, `status`) VALUES
('5006', 'ReAct智能助手',
'你是一个智能助手，可以调用工具来获取信息并回答用户的问题。

可用工具：
- read_file(relativePath): 读取工作目录下指定相对路径的文本文件
- write_file(relativePath, content): 在工作目录下写入文本文件
- run_bash(command): 在工作目录内执行一条白名单内的 shell 命令

当用户的问题需要获取文件内容、文件系统信息或执行命令行操作时，
请使用以上工具获取所需信息，然后结合结果给出完整回答。
如果不需要使用工具，直接回答即可。

工作目录为项目根目录。
', 'ReAct 智能体系统提示词', 1)
ON DUPLICATE KEY UPDATE `prompt_content`=VALUES(`prompt_content`);

-- 2. ReAct 客户端
INSERT INTO `ai_client` (`client_id`, `client_name`, `description`, `status`) VALUES
('3106', 'ReAct智能体Client', 'ReAct 推理+工具调用循环客户端', 1)
ON DUPLICATE KEY UPDATE `client_name`=VALUES(`client_name`);

-- 3. 关联：3106 → model 2001(DeepSeek) / prompt 5006
INSERT INTO `ai_client_config` (`source_type`, `source_id`, `target_type`, `target_id`, `ext_param`, `status`) VALUES
('client', '3106', 'model',  '2001', '', 1),
('client', '3106', 'prompt', '5006', '', 1);