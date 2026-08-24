# AI Agent Station

面向业务反馈评测与 Agent 运行时编排的本地工作台。项目把“为不同业务配置 Agent、让 Agent 获取反馈、按业务规则评测并交给开发人员处理”做成了一条可运行的链路。

当前仓库以库存业务为落地示例，同时保留了通用的 Agent、MCP、Skill、记忆、Case 和运行日志能力，适合用于学习和演示 Java/Spring AI Agent 的工程化实现。

## 这个项目解决什么问题

开发人员每天会从用户反馈、运维记录和业务系统中寻找真实问题。原始反馈通常是自然语言，存在以下困难：

- 不同业务的反馈混在一起，无法直接判断是否属于当前 Agent；
- 反馈缺少商品、现象、影响范围等关键信息，不能直接进入研发处理；
- 同一问题可能在多个渠道重复出现，需要保留原文证据并进行归并；
- Agent 调用 MCP、Skill、模型后的过程难以追踪，出现失败时不容易定位；
- 长对话和工具结果会持续膨胀，既增加模型成本，也容易丢失关键上下文。

本项目的核心做法是：把业务规则写入 Skill，把外部数据访问封装为 MCP，把模型推理限制在 Agent 已绑定的能力范围内；模型只生成候选结论，Case 的发布、处理和关闭仍由开发人员审核。

## 核心功能

### 1. Agent 运行时装配

一个 Agent 可以独立配置：

- 对话模型和执行模式；
- Soul/系统提示词；
- 业务 Skill；
- MCP 服务和工具白名单；
- 工作区及记忆策略。

会话开始时，服务端根据 Agent 的绑定关系装配可用能力。新增或替换模型、Skill、MCP 时不需要改业务流程代码，适合按库存、订单、支付等业务分别配置 Agent。

### 2. Chat、ReAct、Auto 多执行引擎

- **Chat**：处理问答、澄清和反馈记录等不需要工具的请求；
- **ReAct**：模型在“思考 → 选择工具 → 获取结果 → 继续判断”之间循环，适合需要 MCP 查询的任务；
- **Auto**：按“意图识别 → 任务分析 → 精准执行 → 质量监督 → 结果总结”推进复杂任务，监督不通过时可以回到分析阶段重新规划。

系统会限制可用工具、推理轮次和失败重试次数，并将模型、工具、子 Agent 和状态变化写入运行轨迹，便于复盘。

### 3. Feedback-to-Case 评测闭环

以库存 Agent 为例：

1. Agent 通过库存 Feedback MCP 获取当日用户、运维和监控反馈；
2. Agent 按需读取库存业务 Skill，确认缺货、库存不一致、下单失败等规则；
3. 大模型整理反馈内容，判断是否属于库存业务、信息是否充分，并给出缺失项；
4. 证据充分且持续需要跟踪的问题进入候选 Case；
5. 开发人员在工作台审核、发布、处理、合并或关闭 Case；
6. 已解决且具备复用价值的 Case 才会沉淀为该 Agent 的长期业务画像。

Feedback、AI Signal 和 Case 是三个不同层次：Feedback 是原始事实，Signal 是模型观察，Case 是经过证据校验并等待人工处理的业务问题。

### 4. MCP 全生命周期管理

支持从注册到绑定的管理流程：

~~~text
注册 → 版本 → 连通性检查 → 工具发现 → 安全扫描 → 沙箱测试 → 审核 → 发布 → Agent 绑定
~~~

当前提供本地 STDIO MCP 示例，也支持 HTTP/Streamable HTTP 的配置入口。工具发现后，模型可以先看到工具摘要，再按需获取完整 Schema，避免一次性把所有 MCP 工具参数塞入上下文。

### 5. Skill 渐进式加载

Skill 以目录或 ZIP 形式管理，每个 Skill 至少包含根目录 **SKILL.md**。服务端会执行：

~~~text
上传 → 安全解压 → 路径/内容校验 → 安全扫描 → 沙箱测试 → 审核 → 签名发布 → Agent 绑定
~~~

运行时先注入 Skill 的元信息和入口规则，模型确实需要某项业务能力时，再读取对应的业务 Markdown 文件。未绑定的 Skill 不会进入 Agent 上下文。

### 6. 长短期记忆与运行轨迹

- **短期记忆**：完整会话和工具调用持久化到 MySQL；模型推理前通过滚动摘要和分级折叠控制上下文规模，保留当前任务、近期步骤和结构化状态；
- **工具折叠**：折叠后的工具结果保留 **tool_call_id**，模型或服务端可按会话取回原文，不需要重复执行实时工具；
- **长期记忆**：按 Agent 隔离保存已解决 Case 的业务规则、处理经验和版本化 Agent 画像，可选 PostgreSQL/pgvector 或本地 Mem0 适配；
- **可靠性**：摘要任务使用 Redis 分布式锁避免同一会话重复折叠，失败时保留原始消息并支持重试。

### 7. 子 Agent 与可观测日志

主 Agent 可以将相互独立的查询拆分给一级子 Agent。子任务使用独立上下文和工具白名单，支持超时、协作式取消、失败隔离和结果聚合。日志页按 Agent、会话和调用轮次展示模型输入输出、工具参数、工具结果、耗时、状态和错误原因。

## 业务流程图

~~~text
用户/运维反馈
      │
      ▼
库存 Feedback MCP ──► Agent 路由
                         │
                         ├─ Chat：记录反馈/补充信息
                         └─ ReAct/Auto：查询 MCP、读取 Skill、整理结论
                                      │
                                      ▼
                         Feedback / Signal / 候选 Case
                                      │
                                      ▼
                         开发人员审核、处理、关闭
                                      │
                                      ▼
                         Agent 长期业务画像
~~~

## 技术架构

~~~text
Vue 3 + Vite
      │  /api/v1
Spring Boot 3
      ├─ Agent 路由与执行策略（Chat / ReAct / Auto）
      ├─ Skill 目录扫描与渐进式读取
      ├─ MCP 注册、发现、调用与审核
      ├─ Feedback / Signal / Case 评测链路
      ├─ 记忆折叠、摘要、长期记忆适配
      └─ 运行日志与会话工作台
      │
      ├─ MySQL：Agent、会话、消息、工具调用、Feedback、Case
      ├─ PostgreSQL + pgvector：可选长期记忆
      └─ Redis：摘要互斥锁与运行协调
~~~

| 模块 | 作用 |
| --- | --- |
| **ai-agent-station-study-domain** | Agent、会话、记忆、Feedback、Case 等领域模型和规则 |
| **ai-agent-station-study-infrastructure** | MyBatis DAO、数据库访问、模型与 MCP 持久化 |
| **ai-agent-station-study-trigger** | Web 接口、Agent 执行策略、日志和管理 API |
| **ai-agent-station-study-app** | Spring Boot 启动模块和环境配置 |
| **frontend-vue** | Vue 3 工作台，包括 Agent、MCP、Skill、对话、Case 和日志页面 |
| **mcp-test-server** | 可本地启动的库存 Feedback MCP 示例 |
| **skills** | 业务 Skill、Skill 扫描和演示包 |

## 本地启动

### 环境要求

- JDK 21
- Maven 3.9+
- Docker Desktop（推荐）
- IntelliJ IDEA
- Node.js 18+（仅启动 Vue 前端时需要）

### 方式一：Docker 依赖 + IDEA 启动后端

在项目根目录执行：

~~~powershell
.\\scripts\\local-env.ps1 reset
.\\scripts\\local-env.ps1 start
.\\scripts\\local-env.ps1 verify
~~~

**reset** 只删除本项目 Compose 管理的 MySQL/pgvector 数据卷，适合本地重新初始化；已有重要数据时只执行 **start**。

在 IDEA 中：

1. 打开根目录 **pom.xml**，Project SDK 和 Maven Runner 选择 JDK 21；
2. 运行 **ai-agent-station-study-app** 模块的 **cn.bugstack.ai.Application**；
3. 添加启动参数 **--spring.profiles.active=dev**；
4. Docker 模式下配置 **MYSQL_PORT=13306**、**MYSQL_PASSWORD=123456**；
5. 访问 <http://localhost:8091>。

默认依赖地址：

| 服务 | 地址 | 账号 | 密码 | 数据库 |
| --- | --- | --- | --- | --- |
| Docker MySQL | **127.0.0.1:13306** | **root** | **123456** | **ai-agent-station-study** |
| 本机 MySQL | **127.0.0.1:3306** | **root** | **1234** | **ai-agent-station-study** |
| pgvector | **127.0.0.1:15432** | **postgres** | **123456** | **ai-rag-knowledge** |
| Redis | **127.0.0.1:16379** | - | - | - |

Redis 地址来自开发配置；当前 **compose.local.yml** 只负责 MySQL 和 pgvector，Redis 需要你自行启动已有实例或按环境变量改成可用地址。

如果使用本机 MySQL，可先执行 **.\\scripts\\prepare-native-mysql.ps1** 初始化表结构和迁移。

### 方式二：启动 Vue 前端

~~~powershell
cd frontend-vue
npm install
npm run dev
~~~

前端默认访问 <http://localhost:5173>，Vite 会把 **/api/v1** 代理到 <http://localhost:8091>。

## 库存 Feedback MCP 示例

该示例是一个可直接在本机运行的 STDIO MCP Server，内置静态反馈数据，方便验证 Agent 的工具调用链路：

~~~powershell
python .\\mcp-test-server\\inventory_feedback_mcp.py
~~~

在控制台配置 MCP 版本时，使用类似下面的 JSON（Windows 路径可替换为自己的绝对路径）：

~~~json
{
  "command": "python",
  "args": [
    "D:/javacode/ai-coding/ai-agent-station-study/mcp-test-server/inventory_feedback_mcp.py"
  ],
  "env": {
    "INVENTORY_FEEDBACK_MCP_DATA_DIR": "D:/javacode/ai-coding/ai-agent-station-study/mcp-test-server/data"
  }
}
~~~

示例工具包括：

- **get_today_feedback**：按来源和数量查询今日反馈；
- **get_feedback_detail**：查询单条反馈；
- **list_inventory_services**：列出库存相关服务；
- **search_feedback_by_keyword**：按关键字搜索反馈；
- **mark_feedback_triaged**：写入演示用分诊结论。

推荐创建 **inventory-agent**，选择 ReAct，绑定 **inventory-feedback-mcp** 和 **inventory-feedback-agent** Skill，然后在对话中输入：

> 查询今天的库存反馈，先按紧急程度整理；对于信息不足的反馈列出需要补充的内容。

这个 MCP 的数据是演示数据。接入真实系统时，只需替换 MCP Server 内部的数据访问逻辑，并保留工具 Schema 和返回结构即可。

## 常用接口

| 能力 | 接口前缀 |
| --- | --- |
| Agent、会话、消息、绑定关系 | **/api/v1/agents** |
| Agent 对话与 Auto 执行 | **/api/v1/agent/auto_agent** |
| Feedback、Signal、Case、记忆 | **/api/v1/agents/{agentId}** |
| MCP/Skill 注册、版本、审核、发布 | **/api/v1/capabilities** |
| 仪表盘、运行日志、会话轨迹 | **/api/v1/dashboard** |

完整接口可直接查看 **ai-agent-station-study-trigger/src/main/java** 下的 Controller，以及启动后的 OpenAPI/接口日志。

## 数据库与迁移

初始化表结构位于：

- **create_tables.sql**
- **ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/**
- **ai-agent-station-study-app/src/main/resources/sql/pgsql/**

Docker 新卷会自动执行挂载的 MySQL 初始化和迁移脚本；已有数据卷请使用 **.\\scripts\\prepare-native-mysql.ps1** 或按迁移版本补齐。生产环境应通过环境变量或密钥服务覆盖默认连接信息。

## 回归验证

领域规则、安全边界和迁移契约测试：

~~~powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false "-Dtest=CaseScoringServiceTest,WorkflowTransitionPolicyTest,ExplicitFeedbackRequestTest,AnalysisResultParserTest,EnterpriseSchemaContractTest,RollingSummaryPolicyTest,CapabilityApprovalPolicyTest,SafeSkillArchiveValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
~~~

命令行构建：

~~~powershell
mvn -DskipTests package
~~~

部分历史测试会依赖外部模型或预先装配的动态 Bean；运行全量测试前，请先准备对应模型密钥和本地依赖。

## 密钥安全

- 所有模型和外部 MCP 凭据都通过环境变量或密钥服务注入，禁止把真实 Key 写入 Java、YAML、JSON 或测试代码；
- 百度搜索 MCP 测试使用 **BAIDU_APPBUILDER_API_KEY** 环境变量，未配置时会明确提示缺少变量；
- 如果凭据曾经进入公开仓库，应立即在对应平台禁用并重新生成，再清理代码和提交历史；
- 仓库中的 MySQL、PostgreSQL 示例密码仅用于本地开发，不能作为生产凭据。

## 文档索引

- [架构总览](docs/architecture-overview.md)
- [本地开发与启动](docs/local-development.md)
- [库存 Feedback Agent 快速接入](docs/inventory-feedback-agent-quickstart.md)
- [Feedback/Case 业务设计](docs/feedback-ops-project.md)
- [记忆折叠设计](docs/memory-folding.md)
- [Case 证据门禁运行手册](docs/case-evidence-gate-runbook.md)
- [Mem0 本地长期记忆](docs/dev-ops/mem0-local.md)

## 当前边界

- 当前以单租户、按 Agent 隔离为主，尚未扩展完整多租户权限体系；
- 库存 MCP 使用本地静态数据，真实环境需要替换为带鉴权的反馈系统；
- 模型只负责评测和生成候选 Case，发布、处理、关闭仍需要人工审核；
- 周期性批量聚合、复杂语义去重和自动合并属于后续扩展，不作为当前版本的已实现能力；
- 默认密钥和密码仅用于本地开发，禁止直接用于生产环境。

## License

本项目用于个人学习、面试项目展示和本地工程实践。
