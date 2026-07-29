# AI Agent Station

本地企业级 Agent 运营工作台。当前版本采用单租户模型，包含 Agent 对话、显式用户 Feedback、异步 AI Signal/Case 提取、短期记忆折叠，以及 MCP/Skill 的审核发布供应链。

## 本地依赖

- JDK 21
- Maven 3.9+
- Docker Desktop
- IntelliJ IDEA

启动 MySQL 8 与 pgvector：

```powershell
docker compose -f compose.local.yml up -d
docker compose -f compose.local.yml ps
```

默认连接：

| 服务 | 地址 | 用户 | 密码/数据库 |
|---|---|---|---|
| MySQL | `127.0.0.1:13306` | `root` | `123456` / `ai-agent-station-study` |
| pgvector | `127.0.0.1:15432` | `postgres` | `123456` / `ai-rag-knowledge` |

新数据卷会自动执行 `ai-agent-station-study-app/src/main/resources/sql/mysql/migrations/V20260717__enterprise_agent_operations.sql`。已有数据卷可手动重复执行该幂等迁移。

## IDEA 启动

1. 用 IDEA 打开根目录 `pom.xml`，将 Project SDK/Maven Runner JRE 设为 JDK 21。
2. 运行 `ai-agent-station-study-app` 模块中的 `Application`。
3. Program arguments 设置为 `--spring.profiles.active=dev`。
4. 模型密钥通过环境变量或数据库密文引用提供，不要写入源码。
5. 打开 <http://localhost:8091>。

命令行构建：

```powershell
mvn -DskipTests package
java -jar ai-agent-station-study-app/target/ai-agent-station-study-app.jar --spring.profiles.active=dev
```

## 运营模型

- Feedback 只接受用户对具体 assistant message 的显式点赞、点踩或评论；模型推断单独进入 AI Signal。
- Case 按 Agent 隔离，模型输出经过严格 JSON 校验、证据留存、加权评分和人工状态审核。
- 短期记忆由滚动摘要、最近原文、结构化状态、压缩工具结果组成；长期记忆目前只保留扩展接口。
- MCP 流程：注册 → 连通性 → 工具发现 → 安全扫描 → 沙箱测试 → 双人审核 → 发布 → Agent 绑定。
- Skill 流程：ZIP 隔离上传 → 安全解压/校验 → 扫描 → 沙箱测试 → 双人审核 → 签名 → 发布 → Agent 绑定。

主要 API 前缀：

- Agent 运营：`/api/v1/agents/{agentId}`
- MCP/Skill 供应链：`/api/v1/capabilities`
- 前端页面：`/`

## 回归验证

领域规则、安全边界与迁移契约测试：

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false "-Dtest=CaseScoringServiceTest,WorkflowTransitionPolicyTest,ExplicitFeedbackRequestTest,AnalysisResultParserTest,EnterpriseSchemaContractTest,RollingSummaryPolicyTest,CapabilityApprovalPolicyTest,SafeSkillArchiveValidatorTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

仓库中的部分历史测试会直接调用外部模型或依赖预先装配的动态 Bean，不属于离线单元测试，运行全量测试前需准备对应外部环境。
