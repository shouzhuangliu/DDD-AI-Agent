# 阶段 02：库存问题分类

本阶段对应 Skill 规则：`INV-BUSINESS-SCOPE`。分类结果必须能回指该规则，不能只依赖关键词命中。

## 目标

判断反馈是否属于库存业务，并归入一个明确类别。

## 推荐分类

- `OUT_OF_STOCK`：缺货 / 无库存 / 需要补货
- `INVENTORY_INCONSISTENT`：库存显示与真实库存不一致
- `RESERVATION_NOT_RELEASED`：锁库存未释放
- `DEDUCTION_FAILED`：下单后库存未扣减
- `OVERSOLD_RISK`：前台可售但后台库存不足，存在超卖风险
- `REPLENISH_DELAY`：补货或同步延迟
- `NON_INVENTORY`：不属于库存业务

## 判断线索

- 关键词：缺货、补货、库存、超卖、扣减、锁库、同步延迟、可售、售罄
- 页面线索：商品详情、库存接口、下单页、购物车、履约页
- 业务对象：SKU、SPU、仓库、门店、库存池

## 输出要求

对每条候选反馈给出：

- 是否属于库存业务
- 分类结果
- 判断依据
- 缺失信息

## 何时进入下一步

当一条反馈已被确定为库存业务问题后，进入 `03-urgency-evaluation.md`
