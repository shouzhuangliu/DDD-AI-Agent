package cn.bugstack.ai.domain.agent.service.skills;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Skills 目录扫描器。
 * <p>
 * 扫描 {@code {workDir}/skills/} 下的一级子目录，每个目录视为一个 skill，
 * 读取其中的 SKILL.md 文件并解析 frontmatter。
 * <p>
 * 不依赖数据库，纯文件系统驱动。
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Service
public class SkillScannerService {

    @org.springframework.beans.factory.annotation.Value("${spring.ai.agent.react.skills-dir:${user.dir}/skills}")
    private String configuredSkillsDir;

    /**
     * 扫描指定目录下的所有 skills。
     *
     * @param skillsDir skills 根目录（如 {@code D:/javacode/.../skills}）
     * @return 按 skillId 排序的 skill 列表
     */
    public List<SkillInfo> scan(Path skillsDir) {
        List<SkillInfo> result = new ArrayList<>();
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            log.warn("Skills 目录不存在: {}", skillsDir);
            return result;
        }

        try {
            List<Path> skillDirs = new ArrayList<>();
            try (var dirs = Files.list(skillsDir)) {
                dirs.filter(Files::isDirectory).forEach(skillDirs::add);
            }
            for (String category : List.of("public", "custom")) {
                Path categoryDir = skillsDir.resolve(category);
                if (!Files.isDirectory(categoryDir)) continue;
                try (var paths = Files.walk(categoryDir)) {
                    paths.filter(Files::isDirectory)
                            .filter(path -> Files.isRegularFile(path.resolve("SKILL.md")))
                            .forEach(skillDirs::add);
                }
            }
            skillDirs.stream()
                    .distinct()
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(skillDir -> {
                        try {
                            SkillInfo info = readSkillMetadata(skillDir);
                            if (info != null) result.add(info);
                        } catch (Exception e) {
                            log.warn("读取 skill 失败 [{}]: {}", skillDir.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("扫描 skills 目录失败: {}", e.getMessage());
        }

        return result;
    }

    public List<SkillInfo> scanFromWorkDir(String workDir) {
        Map<String, SkillInfo> result = new LinkedHashMap<>();
        for (Path root : candidateSkillsRoots(workDir)) {
            if (!Files.isDirectory(root)) continue;
            for (SkillInfo skill : scan(root)) {
                result.putIfAbsent(skill.getSkillId(), skill);
            }
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 读取单个 skill 目录的 SKILL.md。
     *
     * @param skillDir skill 子目录（如 {@code .../skills/demo-skill/}）
     * @return SkillInfo；若 SKILL.md 缺失或无效返回 null
     */
    public SkillInfo readSkill(Path skillDir) {
        Path mdFile = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(mdFile)) {
            return null;
        }
        try {
            String content = Files.readString(mdFile);
            var parsed = SkillFrontmatterParser.parse(content);
            if (parsed.getName().isBlank()) {
                log.warn("SKILL.md 缺少 name: {}", mdFile);
                return null;
            }
            return SkillInfo.builder()
                    .skillId(skillDir.getFileName().toString())
                    .skillName(parsed.getName())
                    .description(parsed.getDescription())
                    .content(content)
                    .build();
        } catch (IOException e) {
            log.warn("读取 SKILL.md 失败: {}", e.getMessage());
            return null;
        }
    }

    /** Read registry metadata; the model loads the full file on demand. */
    public SkillInfo readSkillMetadata(Path skillDir) {
        SkillInfo skill = readSkill(skillDir);
        return skill == null ? null : SkillInfo.builder()
                .skillId(skill.getSkillId())
                .skillName(skill.getSkillName())
                .description(skill.getDescription())
                .content("")
                .build();
    }

    /**
     * 从运行工作目录解析 Skill。IDEA 常把 user.dir 设置到 app 子模块，
     * 因此这里会从 workDir / user.dir / 当前目录逐级向上查找 skills/{skillId}/SKILL.md。
     */
    public SkillInfo readSkillFromWorkDir(String workDir, String skillId) {
        if (skillId == null || skillId.isBlank()) return null;
        for (Path skillsRoot : candidateSkillsRoots(workDir)) {
            SkillInfo skill = readSkill(skillsRoot.resolve(skillId.trim()));
            if (skill != null) return skill;
        }
        return null;
    }

    public SkillInfo readSkillMetadataFromWorkDir(String workDir, String skillId) {
        if (skillId == null || skillId.isBlank()) return null;
        for (Path skillsRoot : candidateSkillsRoots(workDir)) {
            SkillInfo skill = readSkillMetadata(skillsRoot.resolve(skillId.trim()));
            if (skill != null) return skill;
        }
        return null;
    }

    private List<Path> candidateSkillsRoots(String workDir) {
        Set<Path> roots = new LinkedHashSet<>();
        addConfiguredSkillsRoot(roots, configuredSkillsDir);
        addWorkspaceSkillsRoots(roots, workDir);
        return roots.stream().toList();
    }

    private void addConfiguredSkillsRoot(Set<Path> roots, String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) return;
        Path root = Path.of(configuredRoot);
        if (!root.isAbsolute()) root = Path.of(System.getProperty("user.dir")).resolve(root);
        roots.add(root.toAbsolutePath().normalize());
    }

    private void addWorkspaceSkillsRoots(Set<Path> roots, String baseDir) {
        if (baseDir == null || baseDir.isBlank()) return;
        Path current = Path.of(baseDir).toAbsolutePath().normalize();
        roots.add(current.resolve(".ma").resolve("skills").normalize());
        roots.add(current.resolve("skills").normalize());
    }

    @Value
    @Builder
    public static class SkillInfo {
        /** 目录名，如 demo-skill */
        String skillId;
        /** frontmatter 中的 name */
        String skillName;
        /** frontmatter 中的 description */
        String description;
        /** SKILL.md 全文 */
        String content;
    }
}
