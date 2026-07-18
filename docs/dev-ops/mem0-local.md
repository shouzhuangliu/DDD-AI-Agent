# 长期记忆本地接入

项目现在支持三种长期记忆模式，通过 `LONG_TERM_MEMORY_PROVIDER` 控制：

- `pgvector`：默认模式。使用本地 PostgreSQL/pgvector 存储长期记忆，使用硅基流动 `BAAI/bge-m3` 做 embedding。
- `mem0`：使用外部 Mem0 OSS REST 服务。
- `noop`：关闭跨会话长期记忆，只保留 MySQL 会话记录和短期滚动摘要。

## 推荐本地模式：pgvector + BGE-M3

先确保本地 Docker 数据库已启动：

```powershell
docker compose -f compose.local.yml up -d mysql pgvector
```

然后给应用进程设置 embedding key。不要把 key 写进 Git：

```powershell
$env:LONG_TERM_MEMORY_PROVIDER = "pgvector"
$env:SILICONFLOW_BASE_URL = "https://api.siliconflow.cn"
$env:SILICONFLOW_API_KEY = "<your-siliconflow-key>"
$env:SILICONFLOW_EMBEDDING_MODEL = "BAAI/bge-m3"
```

聊天模型可以继续使用商汤：

```powershell
$env:SENSENOVA_API_KEY = "<your-sensenova-key>"
```

应用侧写入策略不是逐消息写入。只有当单个会话超过短期记忆阈值后，系统先生成短期滚动摘要，再把摘要作为一条长期记忆写入向量库。默认阈值：

```yaml
agent:
  memory:
    token-budget: 16000
    retain-messages: 24
```

召回时按 `agentId` 隔离，不区分用户。每次根据当前用户输入最多召回 3 条同 Agent 的长期记忆，并注入为 `[跨会话长期记忆] ...`。

## 可选模式：Mem0

如果要试 Mem0 OSS REST 服务：

```powershell
$env:LONG_TERM_MEMORY_PROVIDER = "mem0"
$env:MEM0_LLM_API_KEY = $env:SENSENOVA_API_KEY
$env:MEM0_LLM_BASE_URL = "https://token.sensenova.cn/v1"
$env:MEM0_LLM_MODEL = "sensenova-6.7-flash-lite"
$env:MEM0_EMBEDDING_API_KEY = $env:SILICONFLOW_API_KEY
$env:MEM0_EMBEDDING_BASE_URL = "https://api.siliconflow.cn/v1"
$env:MEM0_EMBEDDING_MODEL = "BAAI/bge-m3"
docker compose -f compose.local.yml --profile mem0 up -d mem0
```

应用访问地址默认是：

```powershell
$env:MEM0_BASE_URL = "http://127.0.0.1:18888"
```

本地 compose 使用 `AUTH_DISABLED=true`，只适合开发。生产环境需要私有网络、TLS、鉴权和 `MEM0_API_KEY`。

## 关闭长期记忆

```powershell
$env:LONG_TERM_MEMORY_PROVIDER = "noop"
```

关闭后仍然会保存原始对话、会话列表和短期摘要，只是不再做跨会话长期召回。
