package cn.bugstack.ai.trigger.service.tools;

import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.tools.internal.FileReadTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReadToolBoundaryTest {

    @TempDir
    Path workDir;

    @AfterEach
    void tearDown() {
        ReActToolContextHolder.clear();
    }

    @Test
    void implicitSkillReadFileCanOnlyReadBoundSkillVirtualPath() throws Exception {
        Files.writeString(workDir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(workDir.resolve(".ma/skills/demo-skill"));
        Files.writeString(workDir.resolve(".ma/skills/demo-skill/SKILL.md"), "demo skill manual");
        Files.createDirectories(workDir.resolve(".ma/skills/other-skill"));
        Files.writeString(workDir.resolve(".ma/skills/other-skill/SKILL.md"), "other skill manual");

        ReActToolContextHolder.set(ReActToolContext.builder()
                .agentId("cs")
                .sessionId("sess-1")
                .workDir(workDir)
                .boundSkillIds(List.of("demo-skill"))
                .allowedTools(List.of(ReActToolAllowlistPolicy.READ_FILE))
                .explicitToolIds(List.of())
                .build());

        FileReadTool tool = new FileReadTool();

        assertTrue(tool.readFile(".ma/skills/demo-skill/SKILL.md").contains("demo skill manual"));
        assertTrue(tool.readFile("pom.xml").contains("未授权"));
        assertTrue(tool.readFile(".ma/skills/other-skill/SKILL.md").contains("未授权"));
    }

    @Test
    void explicitReadFileCanReadWorkspaceFile() throws Exception {
        Files.writeString(workDir.resolve("README.md"), "project readme");
        ReActToolContextHolder.set(ReActToolContext.builder()
                .agentId("cs")
                .sessionId("sess-1")
                .workDir(workDir)
                .boundSkillIds(List.of())
                .allowedTools(List.of(ReActToolAllowlistPolicy.READ_FILE))
                .explicitToolIds(List.of(ReActToolAllowlistPolicy.READ_FILE))
                .build());

        assertTrue(new FileReadTool().readFile("README.md").contains("project readme"));
    }
}
