package cn.bugstack.ai.domain.agent.service.tools.internal;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ReAct 内部工具：在工作目录内执行白名单命令。
 * <p>
 * 跨平台：Windows 下自动用 cmd.exe /c 包裹（支持 dir 等 cmd 内建命令），
 * Unix 下直接执行。命令首段须命中白名单。
 */
@Slf4j
@Component
public class BashTool extends AbstractReActTool {

    @Resource
    private ReActToolProperties properties;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @Tool(description = "在工作目录内执行一条白名单内的 shell 命令。Windows 下可用 dir/type/more/findstr 等，Unix 下可用 ls/cat/echo/grep/find 等。参数 command 为完整命令行，危险命令会被拒绝。")
    public String runBash(@ToolParam(description = "要执行的 shell 命令") String command) {
        String toolName = "run_bash";
        String cmd = command == null ? "" : command.trim();
        emitAction(toolName, "执行命令: " + cmd);

        if (!properties.getBash().isEnabled()) {
            String msg = "bash 工具未启用";
            emitObservation(toolName, msg);
            return msg;
        }
        if (cmd.isEmpty()) {
            String msg = "空命令";
            emitObservation(toolName, msg);
            return msg;
        }

        // 白名单校验：取首个 token
        String firstToken = cmd.split("\\s+")[0].toLowerCase();
        int slash = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
        if (slash >= 0) firstToken = firstToken.substring(slash + 1);
        if (firstToken.endsWith(".exe")) firstToken = firstToken.substring(0, firstToken.length() - 4);
        // 去掉 cmd.exe /c 前缀（如果 LLM 自己加了）
        if ("cmd".equals(firstToken)) {
            String[] parts = cmd.split("\\s+");
            if (parts.length >= 3 && "/c".equalsIgnoreCase(parts[1])) {
                firstToken = parts[2].toLowerCase();
                int s2 = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
                if (s2 >= 0) firstToken = firstToken.substring(s2 + 1);
                if (firstToken.endsWith(".exe")) firstToken = firstToken.substring(0, firstToken.length() - 4);
            }
        }
        Set<String> whitelist = properties.bashWhitelist();
        if (!whitelist.isEmpty() && !whitelist.contains(firstToken)) {
            String msg = "命令不在白名单内，已拒绝: " + firstToken + " (允许: " + whitelist + ")";
            log.warn(msg);
            emitObservation(toolName, msg);
            return msg;
        }

        // 工作目录
        Path workDir = null;
        ReActToolContext ctx = ReActToolContextHolder.get();
        if (ctx != null && ctx.getWorkDir() != null) {
            workDir = ctx.getWorkDir().toAbsolutePath().normalize();
        }
        File dir = workDir != null ? workDir.toFile() : new File(".");

        // 构建 ProcessBuilder：Windows 用 cmd.exe /c 包裹
        String[] cmdArray;
        if (IS_WINDOWS) {
            cmdArray = new String[]{"cmd.exe", "/c", cmd};
        } else {
            cmdArray = new String[]{"sh", "-c", cmd};
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            boolean finished = p.waitFor(properties.getBash().getTimeout(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                String msg = "命令执行超时(" + properties.getBash().getTimeout() + "s): " + cmd;
                emitObservation(toolName, msg);
                return msg;
            }
            int exit = p.exitValue();
            String result = out.toString().trim();
            if (result.isEmpty()) {
                result = "(无输出, exit=" + exit + ")";
            } else {
                if (result.length() > 8000) result = result.substring(0, 8000) + "\n...(输出过长，已截断)";
                result = result + "\n[exit=" + exit + "]";
            }
            emitObservation(toolName, result);
            return result;
        } catch (Exception e) {
            String msg = "命令执行异常: " + e.getMessage();
            log.error(msg, e);
            emitObservation(toolName, msg);
            return msg;
        }
    }
}