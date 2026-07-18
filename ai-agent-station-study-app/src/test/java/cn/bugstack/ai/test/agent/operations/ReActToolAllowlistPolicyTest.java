package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class ReActToolAllowlistPolicyTest {

    @Test
    public void defaultToolSetDoesNotExposeProjectInspectionOrMutationTools() {
        ReActToolAllowlistPolicy policy = new ReActToolAllowlistPolicy();

        List<String> tools = policy.resolve(null);

        assertTrue(tools.contains("query_cases"));
        assertTrue(tools.contains("query_feedback"));
        assertFalse(tools.contains("run_bash"));
        assertFalse(tools.contains("write_file"));
        assertFalse(tools.contains("read_file"));
    }

    @Test
    public void explicitBindingsExposeOnlySelectedKnownTools() {
        ReActToolAllowlistPolicy policy = new ReActToolAllowlistPolicy();

        List<String> tools = policy.resolve(List.of("run_bash", "read_file", "unknown_tool"));

        assertTrue(tools.contains("run_bash"));
        assertTrue(tools.contains("read_file"));
        assertFalse(tools.contains("unknown_tool"));
        assertFalse(tools.contains("write_file"));
    }

    @Test
    public void explicitEmptyBindingExposesNoTools() {
        ReActToolAllowlistPolicy policy = new ReActToolAllowlistPolicy();

        assertEquals(0, policy.resolve(List.of()).size());
    }
}
