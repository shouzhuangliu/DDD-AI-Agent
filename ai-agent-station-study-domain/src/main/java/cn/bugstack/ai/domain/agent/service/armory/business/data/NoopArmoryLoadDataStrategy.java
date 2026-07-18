package cn.bugstack.ai.domain.agent.service.armory.business.data;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;

/**
 * 暂无独立数据加载需求的 Armory 命令使用该策略占位。
 * <p>
 * 这些命令通常作为 Client/Model 装配链路的一部分被加载，保留独立策略名可以避免
 * 枚举声明了策略但 Spring Map 中不存在对应实现，导致启动阶段注入失败。
 */
public class NoopArmoryLoadDataStrategy implements ILoadDataStrategy {

    @Override
    public void loadData(ArmoryCommandEntity requestParameter,
                         DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {
        // no-op
    }
}
