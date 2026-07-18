package cn.bugstack.ai.domain.agent.service.tools.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.file.Path;

/**
 * ReAct 工具执行上下文。
 * <p>
 * 每次请求一个实例，由 {@link ReActToolContextHolder} 以 ThreadLocal 暴露给工具方法，
 * 使内部 @Tool 方法能拿到 SSE emitter 推送 action/observation，以及沙箱工作目录。
 *
 * @author ai-agent-station-study
 */
@Data
@Builder
@AllArgsConstructor
public class ReActToolContext {

    /** 会话ID */
    private String sessionId;

    /** Agent ID */
    private String agentId;

    /** SSE 输出流 */
    private ResponseBodyEmitter emitter;

    /** 工具沙箱根目录（读写文件/命令执行都限制在此目录内） */
    private Path workDir;
}
