package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ReActToolContextTest {

    @Test
    public void builderCarriesBoundSkillsAndMcps() {
        ReActToolContext context = ReActToolContext.builder()
                .sessionId("session-1")
                .agentId("cs")
                .workDir(Path.of("."))
                .boundSkillIds(List.of("enterprise-demo-skill-1.0.0"))
                .boundMcpIds(List.of("enterprise-demo-mcp-191056"))
                .build();

        assertEquals(List.of("enterprise-demo-skill-1.0.0"), context.getBoundSkillIds());
        assertEquals(List.of("enterprise-demo-mcp-191056"), context.getBoundMcpIds());
    }
}
