package cn.bugstack.ai.trigger.service.skill;

import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkillScannerRuntimePreferenceTest {

    @Test
    void prefersAgentWorkspaceSkillBeforeGlobalSkillsDirectory() throws Exception {
        Path root = Files.createTempDirectory("skill-runtime-preference");
        try {
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
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }
}
