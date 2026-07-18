package cn.bugstack.ai.domain.agent.service.skills;

import lombok.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SKILL.md frontmatter 解析器。
 * <p>
 * 从 markdown 中提取 --- 块内的 key: value 元数据，返回 name 和 description。
 * 遵循设计文档规范：仅支持简单 key: value，不支持嵌套/列表/多行标量。
 *
 * @author ai-agent-station-study
 */
public final class SkillFrontmatterParser {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---\\s*\\r?\\n?",
            Pattern.MULTILINE);

    private SkillFrontmatterParser() {}

    /**
     * 解析 SKILL.md 文本，返回 frontmatter 元数据 + 正文。
     */
    public static Parsed parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new Parsed(Map.of(), "", "", "");
        }
        Matcher m = FRONTMATTER.matcher(markdown);
        if (!m.find()) {
            return new Parsed(Map.of(), "", "", markdown);
        }

        // 解析 frontmatter key:value
        String block = m.group(1);
        Map<String, String> meta = new HashMap<>();
        for (String line : block.split("\\r?\\n")) {
            line = line.trim();
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                // 去除可选引号
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1).trim();
                }
                meta.put(key, value);
            }
        }

        String body = markdown.substring(m.end()).trim();
        return new Parsed(meta,
                meta.getOrDefault("name", ""),
                meta.getOrDefault("description", ""),
                body);
    }

    /**
     * 自动补齐 frontmatter：若没有 frontmatter，在文件头补入。
     */
    public static String prependFrontmatter(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body;
    }

    @Value
    public static class Parsed {
        Map<String, String> meta;
        String name;
        String description;
        String body;
    }
}