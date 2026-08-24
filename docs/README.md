# 文档索引

这里按“先跑起来 → 理解架构 → 验证业务 → 深入专项”的顺序整理项目文档。

## 快速开始

- [本地开发与启动](local-development.md)：Docker 依赖、IDEA 启动、原生 MySQL 和常用检查命令。
- [库存 Feedback Agent 快速接入](inventory-feedback-agent-quickstart.md)：从创建 Agent、绑定 MCP/Skill 到查询今日反馈的完整演示。

## 架构与业务

- [架构总览](architecture-overview.md)：模块边界、运行时装配、执行策略和数据流。
- [Feedback/Case 业务设计](feedback-ops-project.md)：Feedback、AI Signal、候选 Case、人工审核和长期业务画像。
- [Case 证据门禁运行手册](case-evidence-gate-runbook.md)：候选 Case 的证据要求、状态流转和人工处理。

## Agent 能力

- [记忆折叠设计](memory-folding.md)：滚动摘要、工具结果折叠、原文取回和长期记忆边界。
- [Mem0 本地长期记忆](dev-ops/mem0-local.md)：可选的本地长期记忆适配和验证。

## 阅读建议

1. 先按本地开发文档启动 MySQL、pgvector 和后端；
2. 按库存快速接入文档启动本地 Feedback MCP；
3. 创建库存 Agent，绑定库存 Skill 与 MCP，完成一次查询；
4. 再阅读架构和记忆文档，理解模型、工具、业务规则和数据持久化如何协作。

当前仓库以单租户和本地演示数据为主，文档中标注为“后续扩展”的批量聚合、复杂语义去重和生产级鉴权不应视为已经完成的功能。
