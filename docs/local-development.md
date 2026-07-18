# 本地开发环境

## 前置条件

- Java 17 或更高版本（当前环境已使用 JDK 21 验证）
- Maven 3.9+
- Docker Desktop，并启用 Linux 容器引擎
- IntelliJ IDEA

## 初始化数据库

在项目根目录打开 PowerShell：

```powershell
./scripts/local-env.ps1 reset
```

该命令只会删除并重建 `ai-agent-station-study-local` 项目拥有的 Docker 容器和数据卷，不会处理其他 Docker 项目。

后续可使用：

```powershell
./scripts/local-env.ps1 start
./scripts/local-env.ps1 status
./scripts/local-env.ps1 verify
./scripts/local-env.ps1 stop
```

## 从 IDEA 运行

1. 将根目录的 `pom.xml` 作为 Maven 项目导入。
2. Project SDK 和 Maven Runner 选择同一个 JDK 17+；当前机器可直接选择已安装的 JDK 21。
3. 运行 `ai-agent-station-study-app` 模块中的 `cn.bugstack.ai.Application`。
4. 项目默认启用 `dev`；也可以增加 VM 参数 `-Dspring.profiles.active=dev`。
5. 真正调用聊天或向量模型前，在 IDEA Run Configuration 中设置 `OPENAI_API_KEY`。

应用地址为 `http://localhost:8091`。

本地默认连接：

- MySQL：`127.0.0.1:13306`，数据库 `ai-agent-station-study`，用户 `root`，密码 `123456`
- PGVector：`127.0.0.1:15432`，数据库 `ai-rag-knowledge`，用户 `postgres`，密码 `123456`

## 常见问题

如果脚本提示 Docker engine 不可用，请先启动 Docker Desktop，等待状态变为 Running 后重新执行命令。
