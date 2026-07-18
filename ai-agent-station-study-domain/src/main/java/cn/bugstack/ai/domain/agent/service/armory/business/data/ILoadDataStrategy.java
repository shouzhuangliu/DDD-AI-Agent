package cn.bugstack.ai.domain.agent.service.armory.business.data;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Armory data loading extension point.
 */
public interface ILoadDataStrategy {

    void loadData(ArmoryCommandEntity requestParameter,
                  DefaultArmoryStrategyFactory.DynamicContext dynamicContext)
            throws ExecutionException, InterruptedException, TimeoutException;
}
