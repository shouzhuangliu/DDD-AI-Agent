package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientAdvisorTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AiClientAdvisorNode extends AbstractArmorySupport{
    @Resource
    private AiClientNode aiClientNode;
    @Resource
    private VectorStore vectorStore;
    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Advisor 顾问角色{}", JSON.toJSONString(requestParameter));
        List<AiClientAdvisorVO> aiClientAdvisorList = dynamicContext.getValue(dataName());
        if (aiClientAdvisorList == null || aiClientAdvisorList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client advisor");
            return router(requestParameter, dynamicContext);
        }
        for( AiClientAdvisorVO aiClientAdvisorVO : aiClientAdvisorList) {
            // 构建顾问访问对象
            Advisor advisor = createAdvisor(aiClientAdvisorVO);
            // bean
            registerBean(beanName(aiClientAdvisorVO.getAdvisorId()), Advisor.class, advisor);
        }
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientNode;
    }
    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(id);
    }
    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName();
    }
    private Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO) {
        String advisorType = aiClientAdvisorVO.getAdvisorType();
        AiClientAdvisorTypeEnumVO advisorTypeEnum = AiClientAdvisorTypeEnumVO.getByCode(advisorType);
        return advisorTypeEnum.createAdvisor(aiClientAdvisorVO, vectorStore);
    }
}
