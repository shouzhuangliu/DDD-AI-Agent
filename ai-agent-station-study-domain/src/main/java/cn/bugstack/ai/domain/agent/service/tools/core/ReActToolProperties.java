package cn.bugstack.ai.domain.agent.service.tools.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * ReAct 模式工具配置。
 * <pre>
 * spring:
 *   ai:
 *     agent:
 *       react:
 *         work-dir: D:/javacode/ai-agent/ai-agent-station-study
 *         bash:
 *           enabled: true
 *           whitelist: ls,cat,echo,grep,find,wc,head,tail,dir,type
 *         request-timeout: 60
 * </pre>
 *
 * @author ai-agent-station-study
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.agent.react")
public class ReActToolProperties {

    /** 工具沙箱根目录（读写文件/命令执行都限制在此目录内） */
    private String workDir = "./";

    /** bash 工具配置 */
    private Bash bash = new Bash();

    /** 单次模型调用超时（秒） */
    private int requestTimeout = 60;

    @Data
    public static class Bash {
        /** 是否启用 bash 工具 */
        private boolean enabled = true;

        /** 允许的命令白名单（逗号分隔），仅这些命令可执行 */
        private String whitelist = "ls,cat,echo,grep,find,wc,head,tail,dir,type";

        /** 单条命令超时（秒） */
        private int timeout = 10;
    }

    /** 解析白名单为 Set（去空白、转小写） */
    public Set<String> bashWhitelist() {
        if (bash.getWhitelist() == null || bash.getWhitelist().isBlank()) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        Arrays.stream(bash.getWhitelist().split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        return set;
    }
}
