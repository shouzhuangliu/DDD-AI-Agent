package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentMemoryProfileCompilationTest {

    @Test
    void profileIsCompiledOnlyFromPublishedCards() {
        IAgentMemoryProfileDao profileDao = mock(IAgentMemoryProfileDao.class);
        IAgentMemoryCardDao cardDao = mock(IAgentMemoryCardDao.class);
        when(cardDao.queryPublishedByAgent("inventory")).thenReturn(List.of(
                AgentMemoryCard.builder().memoryId("mem-1").agentId("inventory")
                        .memoryType("RESOLVED_CASE").sourceCaseId("case-28")
                        .title("库存扣减后订单创建失败").description("已通过补偿任务释放库存")
                        .contentJson("{\"resolution\":\"释放预占库存\"}").status("PUBLISHED").version(1).build()));
        AgentMemoryProfileService service = new AgentMemoryProfileService(profileDao, cardDao);

        AgentMemoryProfile profile = service.compileLatest("inventory");

        assertTrue(profile.getProfileJson().contains("case-28"));
        assertTrue(profile.getProfileJson().contains("释放预占库存"));
        verify(profileDao).insert(any());
    }
}
