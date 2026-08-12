package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryLifecyclePort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import cn.bugstack.ai.infrastructure.dao.po.AiCase;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 已解决 Case 自动沉淀为对应 Agent 的处理经验；Case 重开时同步软删除。 */
@Component
public class CaseMemoryPublisher {
    private final AgentMemoryLifecyclePort lifecycle;
    private final IAgentMemoryCardDao cardDao;

    public CaseMemoryPublisher(AgentMemoryLifecyclePort lifecycle, IAgentMemoryCardDao cardDao) {
        this.lifecycle = lifecycle;
        this.cardDao = cardDao;
    }

    public void publish(AiCase item, String toStatus, String reason) {
        if (item == null || item.getAgentId() == null || item.getCaseId() == null || toStatus == null) return;
        String status = toStatus.trim().toUpperCase();
        if ("RESOLVED".equals(status)) {
            lifecycle.upsert(new AgentMemoryLifecyclePort.UpsertCommand(
                    item.getAgentId(), "OPERATING_PLAYBOOK", item.getAgentId() + ":resolved-case:" + item.getCaseId(),
                    safe(item.getTitle()), safe(item.getSummary()), JSON.toJSONString(Map.of(
                            "caseId", item.getCaseId(), "caseType", safe(item.getCaseType()),
                            "severity", safe(item.getSeverity()), "resolution", safe(item.getResolution()))),
                    80, false, "CASE", item.getCaseId(), safe(item.getResolution()), safe(reason)));
            return;
        }
        if ("IN_PROGRESS".equals(status)) {
            for (AgentMemoryCard card : cardDao.queryPublishedByCaseId(item.getAgentId(), item.getCaseId())) {
                lifecycle.retire(new AgentMemoryLifecyclePort.RetireCommand(item.getAgentId(), card.getMemoryId(),
                        "CASE", item.getCaseId(), safe(item.getResolution()), safe(reason)));
            }
        }
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "case evidence unavailable" : value; }
}
