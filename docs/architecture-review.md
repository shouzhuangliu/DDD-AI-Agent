# 架构审查报告 — 记忆 / 容错 / 包组织

## 一、记忆系统(当前缺口 & 设计方案)

### 当前状态

```
层级         | 状态           | 说明
会话内记忆   | ⚠️ 半成品      | Auto 模式用了 PromptChatMemoryAdvisor(sessionId 维度)
             |                | ReAct 模式没配 memory advisor,每次独立
会话间记忆   | ❌ 没有         | 关了对话什么都忘了
知识库记忆   | ✅ 基础版       | Skills(SKILL.md) + Case 元数据(ai_case)
反馈流水     | ✅ 刚做完       | ai_feedback 表,已跑通
```

### 缺的三个东西

#### 1. 会话内记忆(ReAct 模式缺失)

ReAct 策略里没挂 Advisor,所以每轮对话模型不记得之前说了什么。

**修复:** ReActExecuteStrategy 的 ChatClient 加 `PromptChatMemoryAdvisor`(跟 Auto 模式一样),用 sessionId 做 key。

#### 2. Session Manager(简单的会话元数据)

每次对话分配 sessionId,但没有任何地方记录 session 元数据(创建时间、Agent、消息数)。

**加一个 ai_session 表:**
```sql
ai_session:
  session_id VARCHAR(64) PK
  agent_id VARCHAR(32)
  message_count INT
  created_at DATETIME
  updated_at DATETIME
```

#### 3. 长期记忆(跨会话的自动知识沉淀)

当前 Case 是手动录入的。应该有:
- 同一 session 内 Agent 解决了问题 → 自动提取为 Case
- 多 session 同一问题 → 自动归并 + 频次递增

**这个比较复杂,需配合 Case 匹配算法(LLM 判断两条反馈是不是同一个问题)。**

### 记忆方案优先序

```
优先    | 改动量  | 内容
P0      | 小     | ReAct 加 PromptChatMemoryAdvisor(会话内记忆修齐)
P1      | 小     | ai_session 表 + 每次对话登记 session
P2      | 中     | 对话结束时自动提取 Case(LLM 总结 + 生成 SKILL.md)
P3      | 大     | 反馈匹配(LLM 判断新旧反馈是否同一问题,归并频次)
```

---

## 二、错误处理与容错(当前缺口)

### 当前容错级别

```
层              | 当前做法                              | 风险
────────────────┼───────────────────────────────────────┼───────────────────
Model 调用      | 全局 try-catch → 推 error 事件         | 模型返回空/乱码无处理
工具执行        | 工具内 try-catch → 返回 error string   | LLM 可能误解错误信息
MCP 调用        | McpCallTool try-catch → return string  | MCP 超时不重试
Auto 监督 FAIL  | 回退到 Step1 重新分析                     | 无限循环风险(已用 maxStep 限制)
策略路由        | 找不到策略 → fallback auto              | ✅ 可以
Agent 不存在    | 推 error 事件                           | ✅ 明确
```

### 需要补的

#### 1. Model 调用容错(层级最高)

```java
// 当前:
String result = chatClient.prompt(msg).call().content();

// 改进:
String result = callWithRetry(msg, 2);  // 重试 2 次
if (result == null || result.isBlank()) {
    result = fallbackReply("抱歉,暂时无法处理,请稍后重试。");
}
```

重试策略:
- 首次失败 → 等 1s 重试
- 二次失败 → 等 3s 重试
- 三次失败 → 用 fallback 回复,记录错误日志

#### 2. 工具执行错误标准化

```java
// 当前: 每个工具自己 try-catch,返回裸字符串
"文件不存在: xxx"
"命令不在白名单: rm"

// 改进: 统一错误包裹,带错误码
ToolResult.failure("FILE_NOT_FOUND", "文件不存在: xxx")
ToolResult.failure("COMMAND_DENIED", "命令不在白名单: rm")
```

这样 LLM 更容易理解错误的性质,前端也可以根据错误码做不同展示。

#### 3. MCP 超时与重试

当前 McpCallTool 里 MCP 调用超时会返回"调用异常: timeout"。应该:
- 如果是超时 → 提示 LLM 重试(可能网络抖动)
- 如果是连接拒绝 → 提示 LLM 该 MCP 不可用,换其他方式
- 连续失败 3 次 → 标记该 MCP 为"不健康"

#### 4. Auto 监督循环的安全网

当前已用 `maxStep` 限制循环次数。应该再加:
- 同一问题 FAIL 超过 2 次 → 跳到 Summary,不再循环
- 记录循环次数到日志,方便调试

#### 5. 全局异常收敛

当前错误分散在各个 Controller 的 try-catch 里,前端收到的是裸错误文本。
应该:统一用 `@ControllerAdvice` + 标准错误体 `{ error: "code", message: "..." }`。

---

## 三、包组织结构(建议版)

```
ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/
├── model/
│   ├── entity/              # 请求/响应实体
│   │   ├── ExecuteCommandEntity.java
│   │   ├── ArmoryCommandEntity.java
│   │   └── AutoAgentExecuteResultEntity.java
│   └── valobj/              # 值对象 / 枚举
│       ├── AiAgentModeEnum.java
│       ├── AiAgentVO.java
│       ├── AiClientTypeEnumVO.java
│       └── ...
│
├── service/
│   ├── execute/             # 执行策略（核心）
│   │   ├── IExecuteStrategy.java
│   │   ├── auto/            # Auto 多步链路
│   │   │   ├── AutoAgentExecuteStrategy.java
│   │   │   ├── step/        # 链路节点
│   │   │   │   ├── RootNode.java
│   │   │   │   ├── Step0IntentClassifierNode.java
│   │   │   │   ├── Step1AnalyzerNode.java
│   │   │   │   ├── Step2PrecisionExecutorNode.java
│   │   │   │   ├── Step3QualitySupervisorNode.java
│   │   │   │   ├── Step4LogExecutionSummaryNode.java
│   │   │   │   └── AbstractExecuteSupport.java
│   │   │   └── state/
│   │   │       └── AutoAgentStateEnum.java
│   │   └── react/           # ReAct 工具循环
│   │       ├── ReActExecuteStrategy.java
│   │       └── ReActExecuteResultEntity.java
│   │
│   ├── tool/                # 工具系统
│   │   ├── core/            # 工具抽象 + 上下文
│   │   │   ├── AbstractReActTool.java
│   │   │   ├── ReActToolContext.java
│   │   │   └── ReActToolContextHolder.java
│   │   ├── internal/        # 内部 @Tool 方法
│   │   │   ├── FileReadTool.java
│   │   │   ├── FileWriteTool.java
│   │   │   └── BashTool.java
│   │   ├── skill/
│   │   │   └── SkillExecuteTool.java
│   │   ├── mcp/
│   │   │   └── McpCallTool.java
│   │   └── config/
│   │       └── ReActToolProperties.java
│   │
│   ├── skill/               # Skills 扫描 + frontmatter
│   │   ├── SkillFrontmatterParser.java
│   │   └── SkillScannerService.java
│   │
│   ├── armory/              # 装配系统
│   │   ├── AbstractArmorySupport.java
│   │   ├── AiClientApiNode.java
│   │   ├── AiClientModelNode.java
│   │   ├── AiClientToolMcpNode.java
│   │   ├── AiClientAdvisorNode.java
│   │   └── AiClientNode.java
│   │
│   └── memory/              # 记忆系统
│       ├── ConversationMemoryService.java   # 会话记忆管理
│       ├── FeedbackService.java             # 反馈记录 + 统计
│       └── CaseService.java                # Case 提取 + 匹配
│
└── adapter/
    └── repository/
        └── IAgentRepository.java
```

**核心原则:**
- `step/` = Auto 多步链路节点
- `react/` = ReAct 策略
- `tool/internal/` = 内置工具
- `tool/mcp/` = MCP 工具
- `tool/skill/` = Skill 工具
- `memory/` = 记忆系统
- `armory/` = 装配系统

---

## 四、建议的下一步(按优先级)

### P0 — 小改动,高价值
1. ReAct 加 `PromptChatMemoryAdvisor`(会话内记忆齐了)
2. Model 调用加重试 + fallback(容错兜底)

### P1 — 新功能,有区分度
3. `ai_session` 表 + 每次对话自动登记
4. 统一的错误响应体(`@ControllerAdvice`)

### P2 — 巩固知识闭环
5. 对话结束后自动提取 Case→生成 SKILL.md
6. Case 匹配:新反馈尝试与已有 Case 匹配(LLM 判断)
7. 工具执行结果标准化(ToolResult 带错误码)

### P3 — 深化的 Agent 能力
8. MCP 健康检查 + 自动降级
9. 多 Agent 协作(一个 Agent 处理不了→转其他 Agent)
10. 前端 Case 详情页(展示 SKILL.md + 历史处理记录)