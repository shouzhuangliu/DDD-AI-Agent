package cn.bugstack.ai.trigger.service.analysis;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.IFeedbackEvaluationJobDao;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.infrastructure.dao.po.FeedbackEvaluationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

class FeedbackEvaluationWorkerTest {

    private IFeedbackEvaluationJobDao jobDao;
    private IAiFeedbackDao feedbackDao;
    private IAgentRepository agentRepository;
    private SkillScannerService skillScannerService;
    private AgentWorkspaceService agentWorkspaceService;
    private FeedbackEvaluationWorker worker;

    @BeforeEach
    void setUp() {
        jobDao = mock(IFeedbackEvaluationJobDao.class);
        feedbackDao = mock(IAiFeedbackDao.class);
        agentRepository = mock(IAgentRepository.class);
        skillScannerService = mock(SkillScannerService.class);
        agentWorkspaceService = mock(AgentWorkspaceService.class);
        worker = new FeedbackEvaluationWorker();
        ReflectionTestUtils.setField(worker, "jobDao", jobDao);
        ReflectionTestUtils.setField(worker, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(worker, "agentRepository", agentRepository);
        ReflectionTestUtils.setField(worker, "skillScannerService", skillScannerService);
        ReflectionTestUtils.setField(worker, "agentWorkspaceService", agentWorkspaceService);
        ReflectionTestUtils.setField(worker, "enabled", true);
    }

    @Test
    void marksConcreteBusinessSupplyGapAsValid() {
        when(jobDao.queryClaimable()).thenReturn(job(1L, "agent-cs", 100L));
        when(jobDao.claim(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        when(feedbackDao.queryById(100L)).thenReturn(AiFeedback.builder()
                .id(100L).agentId("agent-cs").status("OPEN")
                .message("你好我发现咱们业务存在一个空缺商品，具体是一个DDR5的内存，希望补货")
                .build());

        worker.processNext();

        verify(feedbackDao).transitionStatus(100L, "agent-cs", "OPEN", "VALID", "SUPPLY_GAP", "", 0);
        verify(jobDao).markComplete(1L);
    }

    @Test
    void marksVagueBusinessIssueAsNeedMoreInfo() {
        when(jobDao.queryClaimable()).thenReturn(job(2L, "agent-cs", 101L));
        when(jobDao.claim(eq(2L), any(LocalDateTime.class))).thenReturn(1);
        when(feedbackDao.queryById(101L)).thenReturn(AiFeedback.builder()
                .id(101L).agentId("agent-cs").status("OPEN")
                .message("你们商品好像有问题，帮我看一下")
                .build());

        worker.processNext();

        verify(feedbackDao).transitionStatus(101L, "agent-cs", "OPEN", "NEED_MORE_INFO", "ISSUE_REPORT", "", 0);
        verify(jobDao).markComplete(2L);
    }

    @Test
    void marksSkillScopedBusinessIssueAsValidWhenSkillProvidesDomainContext() {
        when(jobDao.queryClaimable()).thenReturn(job(4L, "coupon-agent", 103L));
        when(jobDao.claim(eq(4L), any(LocalDateTime.class))).thenReturn(1);
        when(feedbackDao.queryById(103L)).thenReturn(AiFeedback.builder()
                .id(103L).agentId("coupon-agent").status("OPEN")
                .message("券码核销失败，批次ZX-12用户无法使用")
                .build());
        when(agentRepository.queryAgentById("coupon-agent")).thenReturn(AiAgentVO.builder()
                .agentId("coupon-agent").agentName("券码助手").description("负责券码核销与活动批次问题")
                .workDir("D:\\repo").build());
        when(agentRepository.queryBoundSkillIds("coupon-agent")).thenReturn(java.util.List.of("coupon-skill"));
        when(agentWorkspaceService.resolveWorkDir("coupon-agent", "D:\\repo", System.getProperty("user.dir")))
                .thenReturn(java.nio.file.Path.of("D:\\repo\\.ma\\workspaces\\coupon-agent"));
        when(skillScannerService.readSkillMetadataFromWorkDir("D:\\repo\\.ma\\workspaces\\coupon-agent", "coupon-skill"))
                .thenReturn(SkillScannerService.SkillInfo.builder()
                        .skillId("coupon-skill")
                        .skillName("券码核销技能")
                        .description("处理券码核销、活动批次、兑换失败等业务反馈")
                        .content("")
                        .build());

        worker.processNext();

        verify(feedbackDao).transitionStatus(103L, "coupon-agent", "OPEN", "VALID", "ISSUE_REPORT", "", 0);
        verify(jobDao).markComplete(4L);
    }

    @Test
    void marksNonIssueChitchatAsInvalid() {
        when(jobDao.queryClaimable()).thenReturn(job(3L, "agent-cs", 102L));
        when(jobDao.claim(eq(3L), any(LocalDateTime.class))).thenReturn(1);
        when(feedbackDao.queryById(102L)).thenReturn(AiFeedback.builder()
                .id(102L).agentId("agent-cs").status("OPEN")
                .message("谢谢，收到啦")
                .build());

        worker.processNext();

        verify(feedbackDao).transitionStatus(102L, "agent-cs", "OPEN", "INVALID", "NON_ISSUE", "", 1);
        verify(jobDao).markComplete(3L);
    }

    private static FeedbackEvaluationJob job(Long id, String agentId, Long feedbackId) {
        return FeedbackEvaluationJob.builder()
                .id(id)
                .agentId(agentId)
                .feedbackId(feedbackId)
                .status("PENDING")
                .attempts(0)
                .maxAttempts(3)
                .build();
    }
}
