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
import java.util.List;

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

        try (var dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(skillDir -> {
                        try {
                            SkillInfo info = readSkill(skillDir);
                            if (info != null) {
                                result.add(info);
                            }
                        } catch (Exception e) {
                            log.warn("读取 skill 失败 [{}]: {}", skillDir.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("扫描 skills 目录失败: {}", e.getMessage());
        }

        return result;
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