# 阶段 3：运维事件分诊

## 目标

汇总只读诊断信息，帮助值班人员判断影响范围和当前风险。

## 步骤

1. 调用 `get_service_health` 查询服务健康快照。
2. 调用 `search_incidents` 查询相同服务和时间范围内的事件。
3. 调用 `append_case_evidence` 记录查询结果、采集时间和数据来源。
4. 服务仍异常时，建议人工值班人员止损和升级，不执行生产操作。

## 下一步

完成初步分诊后读取 `references/04-case-evidence.md`。
