# 阶段 5：人工审核

## 目标

让管理人员决定反馈是否升级为正式 Case，避免 Agent 自动制造案件。

## 步骤

1. 汇总分类、优先级、影响范围和证据。
2. 使用 `search_feedback` 检查相似反馈，避免重复升级。
3. 给出 `CONFIRM_CASE`、`REQUEST_MORE_INFO` 或 `IGNORE_DUPLICATE` 建议。
4. 只有收到人工确认后，才调用 `promote_feedback_to_case`。
5. 升级结果必须保持 `PENDING_REVIEW`，后续状态由 Case 工作台操作。

## 下一步

人工确认 Case 后读取 `references/06-case-resolution.md`。
