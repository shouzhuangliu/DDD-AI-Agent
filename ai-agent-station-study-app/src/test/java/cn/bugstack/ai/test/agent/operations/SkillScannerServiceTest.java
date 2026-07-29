package cn.bugstack.ai.test.agent.operations;

import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class SkillScannerServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void findsSkillWhenIdeaWorkDirIsSubModule() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Path appModule = Files.createDirectories(root.resolve("ai-agent-station-study-app"));
        Path skillDir = Files.createDirectories(root.resolve("skills").resolve("enterprise-demo-skill-1.0.0"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: enterprise-demo-skill
                description: demo skill
                ---
                # Enterprise Demo Skill
                """);

        SkillScannerService service = new SkillScannerService();
        SkillScannerService.SkillInfo skill = service.readSkillFromWorkDir(appModule.toString(), "enterprise-demo-skill-1.0.0");

        assertNotNull(skill);
        assertEquals("enterprise-demo-skill-1.0.0", skill.getSkillId());
        assertEquals("enterprise-demo-skill", skill.getSkillName());
    }

    @Test
    public void prefersAgentWorkspaceSkillBeforeGlobalSkillsDirectory() throws Exception {
        Path root = temporaryFolder.newFolder("workspace-project").toPath();
        Path appModule = Files.createDirectories(root.resolve("ai-agent-station-study-app"));
        Path globalSkill = Files.createDirectories(root.resolve("skills").resolve("enterprise-demo-skill-1.0.0"));
        Files.writeString(globalSkill.resolve("SKILL.md"), """
                ---
                name: global-demo-skill
                description: global skill
                ---
                # Global Demo Skill
                """);
        Path runtimeSkill = Files.createDirectories(root.resolve(".ma").resolve("skills").resolve("enterprise-demo-skill-1.0.0"));
        Files.writeString(runtimeSkill.resolve("SKILL.md"), """
                ---
                name: runtime-bound-skill
                description: runtime skill
                ---
                # Runtime Bound Skill
                """);

        SkillScannerService service = new SkillScannerService();
        SkillScannerService.SkillInfo skill = service.readSkillFromWorkDir(appModule.toString(), "enterprise-demo-skill-1.0.0");

        assertNotNull(skill);
        assertEquals("runtime-bound-skill", skill.getSkillName());
    }
}
