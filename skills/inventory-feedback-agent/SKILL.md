---
name: inventory-feedback-agent
description: 面向库存业务的反馈巡检 Skill。先拉取今日 Feedback，再按库存业务规则判断是否需要升级为紧急 Case。
---

# 库存 Feedback 巡检闭环

这是一个渐进式 Skill。

当前文件只定义入口、阶段路由与调用边界，不承载全部业务细节。实际判断时，请只加载当前阶段需要的 reference 文档。

## 目录结构

```text
inventory-feedback-agent/
├── SKILL.md
├── skill.json
└── references/
    ├── 01-feedback-intake.md
    ├── 02-inventory-classification.md
    ├── 03-urgency-evaluation.md
    ├── 04-case-promotion.md
    └── 05-daily-summary.md
```

## 使用规则

1. 当用户要求“查看今日 Feedback / 巡检今日反馈 / 看看有没有紧急 case”时，先读取 `references/01-feedback-intake.md`
2. 必须先调用 MCP 工具 `get_today_feedback`，再做业务判断
3. 只有当反馈涉及库存、缺货、超卖、扣减异常、前台可售但后台无货、补货延迟时，才进入库存业务分类
4. 每次只读取一个 reference；完成一个阶段后，再进入下一个阶段
5. 输出时必须区分：
   - 已确认事实
   - 库存业务判断
   - 紧急度
   - 是否建议升级为 Case
   - 需要人工补充的信息

## 阶段路由

```text
查看今日反馈 -> 01-feedback-intake
确认与库存业务相关 -> 02-inventory-classification
需要判断影响范围与优先级 -> 03-urgency-evaluation
需要判断是否升级 Case -> 04-case-promotion
需要形成晨检/日报结论 -> 05-daily-summary
```

## 可调用 MCP

优先使用当前 Agent 绑定的库存反馈 MCP：

- `get_today_feedback`
- `get_feedback_detail`
- `list_inventory_services`
- `search_feedback_by_keyword`
- `mark_feedback_triaged`

## 权限边界

- 可以读取和标记本地测试 Feedback
- 不允许直接创建真实生产 Case
- 不允许修改生产库存、订单、商品数据
- 不允许读取密钥、Cookie、账号密码等敏感信息
