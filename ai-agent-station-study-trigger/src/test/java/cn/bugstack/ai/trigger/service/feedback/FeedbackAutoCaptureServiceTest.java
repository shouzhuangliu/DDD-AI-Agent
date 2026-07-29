package cn.bugstack.ai.trigger.service.feedback;

import cn.bugstack.ai.infrastructure.dao.IAiFeedbackDao;
import cn.bugstack.ai.infrastructure.dao.po.AiFeedback;
import cn.bugstack.ai.trigger.service.analysis.FeedbackEvaluationJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FeedbackAutoCaptureServiceTest {

    private IAiFeedbackDao feedbackDao;
    private FeedbackEvaluationJobQueue feedbackEvaluationJobQueue;
    private FeedbackAdmissionPolicy feedbackAdmissionPolicy;
    private FeedbackAutoCaptureService service;

    @BeforeEach
    void setUp() {
        feedbackDao = mock(IAiFeedbackDao.class);
        feedbackEvaluationJobQueue = mock(FeedbackEvaluationJobQueue.class);
        feedbackAdmissionPolicy = new FeedbackAdmissionPolicy();
        service = new FeedbackAutoCaptureService();
        ReflectionTestUtils.setField(service, "feedbackDao", feedbackDao);
        ReflectionTestUtils.setField(service, "feedbackEvaluationJobQueue", feedbackEvaluationJobQueue);
        ReflectionTestUtils.setField(service, "feedbackAdmissionPolicy", feedbackAdmissionPolicy);
        doAnswer(invocation -> {
            AiFeedback feedback = invocation.getArgument(0);
            feedback.setId(101L);
            return 1;
        }).when(feedbackDao).insert(any(AiFeedback.class));
    }

    @Test
    void capturesQualifiedBusinessIssueAsUserFeedback() {
        Long feedbackId = service.captureUserIssue("cs", "sess-1",
                "你好我发现咱们业务存在一个空缺商品，具体是 DDR5 的内存，希望尽快补货");

        assertEquals(101L, feedbackId);
        ArgumentCaptor<AiFeedback> captor = ArgumentCaptor.forClass(AiFeedback.class);
        verify(feedbackDao).insert(captor.capture());
        verify(feedbackEvaluationJobQueue).enqueue("cs", 101L);
        AiFeedback saved = captor.getValue();
        assertEquals("USER", saved.getSourceType());
        assertEquals("ISSUE_REPORT", saved.getFeedbackType());
        assertEquals("OPEN", saved.getStatus());
        assertTrue(saved.getMessage().contains("DDR5"));
    }

    @Test
    void skipsTinyOrTestInput() {
        Long feedbackId = service.captureUserIssue("cs", "sess-1", "1");

        assertNull(feedbackId);
        verify(feedbackDao, never()).insert(any());
        verify(feedbackEvaluationJobQueue, never()).enqueue(anyString(), anyLong());
    }
}
