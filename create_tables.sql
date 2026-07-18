SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `ai-agent-station-study` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `ai-agent-station-study`;

CREATE TABLE IF NOT EXISTS `ai_agent` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'agentID',
  `agent_name` VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'agent名称',
  `description` VARCHAR(255) DEFAULT '' COMMENT '描述',
  `system_prompt` TEXT COMMENT '灵魂：system prompt（专属Agent人格/角色）',
  `model_id` VARCHAR(32) DEFAULT '2001' COMMENT '绑定的模型 id',
  `work_dir` VARCHAR(255) DEFAULT '' COMMENT '工具沙箱工作目录(空=用默认)',
  `channel` VARCHAR(32) DEFAULT '' COMMENT '渠道',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0禁用1启用)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent 主表';

CREATE TABLE IF NOT EXISTS `ai_client` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `client_id` VARCHAR(32) NOT NULL DEFAULT '',
  `client_name` VARCHAR(64) NOT NULL DEFAULT '',
  `description` VARCHAR(255) DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 客户端表';

CREATE TABLE IF NOT EXISTS `ai_client_api` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `api_id` VARCHAR(32) NOT NULL DEFAULT '',
  `base_url` VARCHAR(255) NOT NULL DEFAULT '',
  `api_key` VARCHAR(255) NOT NULL DEFAULT '',
  `completions_path` VARCHAR(128) DEFAULT '',
  `embeddings_path` VARCHAR(128) DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_id` (`api_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI API 配置表';

CREATE TABLE IF NOT EXISTS `ai_client_model` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `model_id` VARCHAR(32) NOT NULL DEFAULT '',
  `api_id` VARCHAR(32) NOT NULL DEFAULT '',
  `model_name` VARCHAR(128) NOT NULL DEFAULT '',
  `model_type` VARCHAR(32) NOT NULL DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_id` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型表';

CREATE TABLE IF NOT EXISTS `ai_client_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_type` VARCHAR(32) NOT NULL DEFAULT '',
  `source_id` VARCHAR(32) NOT NULL DEFAULT '',
  `target_type` VARCHAR(32) NOT NULL DEFAULT '',
  `target_id` VARCHAR(32) NOT NULL DEFAULT '',
  `ext_param` VARCHAR(1024) DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_source` (`source_type`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 客户端配置关联表';

CREATE TABLE IF NOT EXISTS `ai_client_advisor` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `advisor_id` VARCHAR(32) NOT NULL DEFAULT '',
  `advisor_name` VARCHAR(64) NOT NULL DEFAULT '',
  `advisor_type` VARCHAR(32) NOT NULL DEFAULT '',
  `order_num` INT NOT NULL DEFAULT 0,
  `ext_param` VARCHAR(1024) DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advisor_id` (`advisor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 顾问表';

CREATE TABLE IF NOT EXISTS `ai_client_system_prompt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `prompt_id` VARCHAR(32) NOT NULL DEFAULT '',
  `prompt_name` VARCHAR(64) NOT NULL DEFAULT '',
  `prompt_content` TEXT,
  `description` VARCHAR(255) DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prompt_id` (`prompt_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 系统提示词表';

CREATE TABLE IF NOT EXISTS `ai_client_tool_mcp` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `mcp_id` VARCHAR(32) NOT NULL DEFAULT '',
  `mcp_name` VARCHAR(64) NOT NULL DEFAULT '',
  `transport_type` VARCHAR(16) NOT NULL DEFAULT '',
  `transport_config` VARCHAR(1024) DEFAULT '',
  `request_timeout` INT NOT NULL DEFAULT 60,
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_id` (`mcp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI MCP 工具表';

CREATE TABLE IF NOT EXISTS `ai_client_rag_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `rag_id` VARCHAR(32) NOT NULL DEFAULT '',
  `rag_name` VARCHAR(64) NOT NULL DEFAULT '',
  `knowledge_tag` VARCHAR(128) DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_id` (`rag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI RAG 订单表';

CREATE TABLE IF NOT EXISTS `ai_agent_flow_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '',
  `client_id` VARCHAR(32) NOT NULL DEFAULT '',
  `client_name` VARCHAR(64) NOT NULL DEFAULT '',
  `client_type` VARCHAR(32) NOT NULL DEFAULT '',
  `sequence` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent 流程配置表';

CREATE TABLE IF NOT EXISTS `ai_agent_task_schedule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '',
  `task_name` VARCHAR(64) NOT NULL DEFAULT '',
  `description` VARCHAR(255) DEFAULT '',
  `cron_expression` VARCHAR(64) NOT NULL DEFAULT '',
  `task_param` VARCHAR(1024) DEFAULT '',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent 定时任务表';

SET FOREIGN_KEY_CHECKS = 1;

-- ==========================================
-- 以下为后续新增表
-- ==========================================

CREATE TABLE IF NOT EXISTS `ai_agent_skill` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '',
  `skill_id` VARCHAR(64) NOT NULL DEFAULT '',
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skill` (`agent_id`, `skill_id`),
  KEY `idx_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 绑定 Skill';

CREATE TABLE IF NOT EXISTS `ai_agent_mcp` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '',
  `mcp_id` VARCHAR(64) NOT NULL DEFAULT '',
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_mcp` (`agent_id`, `mcp_id`),
  KEY `idx_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 绑定 MCP';

CREATE TABLE IF NOT EXISTS `ai_feedback` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL DEFAULT '',
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '',
  `message` TEXT NOT NULL COMMENT '用户原始反馈',
  `category` VARCHAR(32) DEFAULT '' COMMENT '分类: bug/feature/consult/other',
  `matched_case_id` VARCHAR(64) DEFAULT '' COMMENT '匹配到的 case id',
  `resolved` TINYINT DEFAULT 0 COMMENT '是否已解决',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent` (`agent_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户反馈原始流水';

CREATE TABLE IF NOT EXISTS `ai_case` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `case_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '对应 skill_id',
  `title` VARCHAR(200) NOT NULL DEFAULT '' COMMENT 'Case 标题',
  `case_type` VARCHAR(32) DEFAULT 'bug' COMMENT 'bug/runbook/faq/feature',
  `frequency` INT DEFAULT 0 COMMENT '命中次数',
  `status` VARCHAR(16) DEFAULT 'active' COMMENT 'active/archived',
  `skill_id` VARCHAR(64) DEFAULT '' COMMENT '关联 skills/ 目录名',
  `merged_to_case_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '合并目标 Case ID',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case` (`case_id`),
  KEY `idx_freq` (`frequency` DESC),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Case 元数据';

CREATE TABLE IF NOT EXISTS `chat_message` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '会话ID',
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'Agent ID',
  `turn` INT NOT NULL DEFAULT 0 COMMENT '轮次',
  `step` INT NOT NULL DEFAULT 0 COMMENT '步数',
  `role` VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'user/assistant/tool',
  `content` MEDIUMTEXT COMMENT '消息正文(DB原件,永不压缩)',
  `tool_call_id` VARCHAR(64) DEFAULT '' COMMENT '工具调用ID',
  `tool_name` VARCHAR(64) DEFAULT '' COMMENT '工具名称',
  `tool_arguments` TEXT COMMENT '工具参数JSON',
  `tool_calls_json` TEXT COMMENT 'assistant的tool_calls JSON',
  `compressed` TINYINT DEFAULT 0 COMMENT '是否已被折叠',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_tool_call` (`tool_call_id`),
  KEY `idx_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息原件';

CREATE TABLE IF NOT EXISTS `ai_llm_log` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '会话ID',
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'Agent ID',
  `model_name` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '模型名',
  `mode` VARCHAR(16) DEFAULT '' COMMENT 'auto/react',
  `input_tokens` INT DEFAULT 0 COMMENT '输入token数',
  `output_tokens` INT DEFAULT 0 COMMENT '输出token数',
  `total_tokens` INT DEFAULT 0 COMMENT '总token数',
  `duration_ms` INT DEFAULT 0 COMMENT '响应时长(毫秒)',
  `status` VARCHAR(16) DEFAULT 'success' COMMENT 'success/error/fallback',
  `error_message` TEXT COMMENT '错误消息',
  `history_msg_count` INT DEFAULT 0 COMMENT '历史消息数',
  `folded_msg_count` INT DEFAULT 0 COMMENT '折叠后消息数',
  `system_prompt_len` INT DEFAULT 0 COMMENT '系统提示词长度',
  `user_message_len` INT DEFAULT 0 COMMENT '用户消息长度',
  `assistant_response_len` INT DEFAULT 0 COMMENT '回复长度',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_agent` (`agent_id`),
  KEY `idx_agent_time` (`agent_id`, `created_at`),
  KEY `idx_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 调用日志';

CREATE TABLE IF NOT EXISTS `ai_session` (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '会话ID',
  `agent_id` VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'Agent ID',
  `title` VARCHAR(200) DEFAULT '' COMMENT '会话标题',
  `message_count` INT DEFAULT 0 COMMENT '消息数',
  `status` TINYINT DEFAULT 1 COMMENT '1启用',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session` (`session_id`),
  KEY `idx_agent` (`agent_id`),
  KEY `idx_agent_time` (`agent_id`, `updated_at` DESC),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话元数据';

SELECT 'OK' AS result;
