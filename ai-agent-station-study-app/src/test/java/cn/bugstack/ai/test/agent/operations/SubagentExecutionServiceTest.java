package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskState;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.internal.BashTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileReadTool;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileWriteTool;
import cn.bugstack.ai.domain.agent.service.tools.mcp.McpCallTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryCaseTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.QueryFeedbackTool;
import cn.bugstack.ai.domain.agent.service.tools.memory.RetrieveToolCallTool;
import cn.bugstack.ai.domain.agent.service.tools.subagent.SubagentExecutionService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class SubagentExecutionServiceTest {

    private ControlledSubagentService service;
    private ReActToolContext context;

    @Before
    public void setUp() {
        service = new ControlledSubagentService(new GenericApplicationContext());
        service.init();
        context = ReActToolContext.builder()
                .sessionId("test-session")
                .agentId("test-agent")
                .executionId("test-execution")
                .emitter(new ResponseBodyEmitter())
                .workDir(Path.of("."))
                .build();
    }

    @After
    public void tearDown() {
        service.releaseChildren();
        service.shutdown();
    }

    @Test
    public void cancelPendingTaskBecomesTerminalAndIsIdempotent() throws Exception {
        String taskId = service.submit(context, "cancel-me", "prompt");
        SubagentTaskState state = service.find(taskId);

        assertNotNull(state);
        assertTrue(service.cancel(taskId));
        assertTrue(service.awaitStatus(taskId, "CANCELLED", 3000));
        assertFalse(service.cancel(taskId));
    }

    @Test
    public void cancelRunningTaskInterruptsChildFuture() throws Exception {
        String taskId = service.submit(context, "running-cancel", "prompt");
        assertTrue(service.awaitStarted(3000));

        assertTrue(service.cancel(taskId));
        assertTrue(service.awaitStatus(taskId, "CANCELLED", 3000));
        assertFalse(service.cancel(taskId));
    }

    @Test
    public void cancelUnknownTaskReturnsFalse() {
        assertFalse(service.cancel("missing-task"));
    }

    @Test
    public void dispatchTruncatesToThreeTasks() throws Exception {
        String result = service.dispatchAndWait(context, List.of(
                new SubagentExecutionService.TaskInput("A", "a"),
                new SubagentExecutionService.TaskInput("B", "b"),
                new SubagentExecutionService.TaskInput("C", "c"),
                new SubagentExecutionService.TaskInput("D", "d"),
                new SubagentExecutionService.TaskInput("E", "e")));

        assertTrue(result.contains("A"));
        assertTrue(result.contains("B"));
        assertTrue(result.contains("C"));
        assertFalse(result.contains("D"));
        assertFalse(result.contains("E"));
    }

    @Test
    public void threeChildrenRunConcurrently() throws Exception {
        List<SubagentExecutionService.TaskInput> inputs = List.of(
                new SubagentExecutionService.TaskInput("A", "a"),
                new SubagentExecutionService.TaskInput("B", "b"),
                new SubagentExecutionService.TaskInput("C", "c"));

        Thread dispatch = new Thread(() -> service.dispatchAndWait(context, inputs));
        dispatch.start();

        assertTrue("three children did not start concurrently", service.started.await(3, TimeUnit.SECONDS));
        assertEquals(3, service.startedCount());
        service.releaseChildren();
        dispatch.join(5000);
        assertFalse("dispatch did not finish", dispatch.isAlive());
    }

    @Test
    public void fourthChildWaitsBehindConcurrencyGate() throws Exception {
        for (int i = 0; i < 4; i++) {
            service.submit(context, "task-" + i, "prompt-" + i);
        }

        assertTrue(service.started.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(SubagentExecutionService.MAX_CONCURRENT, service.startedCount());
        service.releaseChildren();
    }

    @Test
    public void serviceUsesThreeAsConcurrencyLimit() {
        assertEquals(3, SubagentExecutionService.MAX_CONCURRENT);
    }

    private static final class ControlledSubagentService extends SubagentExecutionService {
        private final CountDownLatch started = new CountDownLatch(MAX_CONCURRENT);
        private final CountDownLatch release = new CountDownLatch(1);

        private ControlledSubagentService(GenericApplicationContext context) {
            super(context, new FileReadTool(), new FileWriteTool(), new BashTool(),
                    new McpCallTool(), new RetrieveToolCallTool(), new QueryCaseTool(), new QueryFeedbackTool());
        }

        @Override
        protected String runInChildContext(ReActToolContext parent, SubagentTaskState state, String prompt) {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("child interrupted", exception);
            }
            return state.getDescription();
        }

        private int startedCount() {
            return MAX_CONCURRENT - (int) started.getCount();
        }

        private boolean awaitStarted(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (startedCount() > 0) return true;
                Thread.sleep(20);
            }
            return false;
        }

        private void releaseChildren() {
            release.countDown();
        }

        private boolean awaitStatus(String taskId, String status, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                SubagentTaskState state = find(taskId);
                if (state != null && status.equals(state.getStatus())) return true;
                Thread.sleep(20);
            }
            return false;
        }
    }
}
