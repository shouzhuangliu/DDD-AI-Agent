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

/**
 * ReAct 内部工具：向沙箱目录下写入文件。
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Component
public class FileWriteTool extends AbstractReActTool {

    @Tool(description = "在工作目录下写入或覆盖一个文本文件。参数 relativePath 为相对工作目录的文件路径，content 为要写入的文本内容。路径不能包含盘符或绝对路径。")
    public String writeFile(@ToolParam(description = "相对工作目录的文件路径") String relativePath,
                            @ToolParam(description = "要写入的文本内容") String content) {
        String toolName = "write_file";
        String preview = content == null ? "" : (content.length() > 100 ? content.substring(0, 100) + "..." : content);
        emitAction(toolName, "写入文件: " + relativePath + " (内容长度=" + (content == null ? 0 : content.length()) + ")");

        Path target = resolveInWorkDir(relativePath);
        if (target == null) {
            String msg = "非法路径(只允许工作目录内相对路径): " + relativePath;
            emitObservation(toolName, msg);
            return msg;
        }
        try {
            // 确保父目录存在
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content == null ? "" : content);
            String msg = "写入成功: " + relativePath + " (工作目录: " + workDirString() + ")";
            emitObservation(toolName, msg);
            return msg;
        } catch (Exception e) {
            String msg = "写入文件失败: " + e.getMessage();
            log.error(msg, e);
            emitObservation(toolName, msg);
            return msg;
        }
    }
}
