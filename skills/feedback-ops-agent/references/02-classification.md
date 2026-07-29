# 阶段 2：反馈分类

## 目标

基于原始反馈确定类别和优先级，决定后续是否进入运维分诊。

## 步骤

1. 调用 `get_feedback_detail` 读取完整反馈。
2. 分类为 `BUG`、`REQUEST`、`QUESTION` 或 `INCIDENT`。
3. 评估 P0、P1、P2、P3：P0 全量不可用，P1 核心流程受阻，P2 部分影响，P3 一般优化。
4. 把分类依据写入回复，不把推测当成系统事实。

## 下一步

`INCIDENT` 或 P0/P1 读取 `references/03-incident-triage.md`；其他情况读取 `references/04-case-evidence.md`。
