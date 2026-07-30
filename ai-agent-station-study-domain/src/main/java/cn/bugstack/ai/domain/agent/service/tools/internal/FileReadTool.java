package cn.bugstack.ai.domain.agent.service.tools.internal;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ReAct 内部工具：读取沙箱目录下的文件内容。
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Component
public class FileReadTool extends AbstractReActTool {

    @Tool(description = "读取工作目录下指定相对路径的文本文件内容。参数 relativePath 为相对工作目录的文件路径，不能包含盘符或绝对路径。")
    public String readFile(@ToolParam(description = "相对工作目录的文件路径") String relativePath) {
        String toolName = "read_file";
        emitAction(toolName, "读取文件: " + relativePath);

        String authorizationError = authorizeRead(relativePath);
        if (authorizationError != null) {
            emitObservation(toolName, authorizationError);
            return authorizationError;
        }

        Path target = resolveInWorkDir(relativePath);
        if (target == null) {
            String msg = "非法路径(只允许工作目录内相对路径): " + relativePath;
            emitObservation(toolName, msg);
            return msg;
        }
        try {
            if (!Files.exists(target)) {
                String msg = "文件不存在: " + relativePath;
                emitObservation(toolName, msg);
                return msg;
            }
            if (Files.isDirectory(target)) {
                String msg = "路径是目录不是文件: " + relativePath;
                emitObservation(toolName, msg);
                return msg;
            }
            String content = Files.readString(target);
            // 限制返回长度，避免超大文件撑爆上下文
            if (content.length() > 8000) {
                content = content.substring(0, 8000) + "\n...(文件过大，已截断)";
            }
            emitObservation(toolName, content);
            return content;
        } catch (Exception e) {
            String msg = "读取文件失败: " + e.getMessage();
            log.error(msg, e);
            emitObservation(toolName, msg);
            return msg;
        }
    }

    private String authorizeRead(String relativePath) {
        ReActToolContext ctx = ReActToolContextHolder.get();
        if (ctx == null) return null;
        List<String> explicitToolIds = ctx.getExplicitToolIds() == null ? List.of() : ctx.getExplicitToolIds();
        if (explicitToolIds.contains("read_file")) {
            return null;
        }
        String normalized = (relativePath == null ? "" : relativePath.trim()).replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.contains("..")) {
            return "未授权读取路径: " + relativePath + "。仅允许读取已绑定 Skill 的虚拟路径 .ma/skills/{skillId}/...";
        }
        if (!normalized.startsWith(".ma/skills/")) {
            return "未授权读取路径: " + relativePath + "。当前 read_file 仅由 Skill 隐式启用，只允许读取已绑定 Skill 的虚拟路径。";
        }
        String rest = normalized.substring(".ma/skills/".length());
        int slash = rest.indexOf('/');
        String skillId = slash < 0 ? rest : rest.substring(0, slash);
        List<String> boundSkillIds = ctx.getBoundSkillIds() == null ? List.of() : ctx.getBoundSkillIds();
        if (skillId.isBlank() || !boundSkillIds.contains(skillId)) {
            return "未授权读取 Skill: " + skillId + "。该 Agent 未绑定此 Skill。";
        }
        return null;
    }
}
