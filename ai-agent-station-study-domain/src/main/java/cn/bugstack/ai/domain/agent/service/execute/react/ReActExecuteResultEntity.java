package cn.bugstack.ai.domain.agent.service.execute.react;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ReAct 执行结果实体（SSE 信封）。
 * <p>
 * 与 AutoAgentExecuteResultEntity 字段同构，但类型语义面向 ReAct 循环：
 * <ul>
 *   <li>type=action     —— 模型决定调用某工具（Thought→Action）</li>
 *   <li>type=observation —— 工具执行返回的结果（Observation）</li>
 *   <li>type=final      —— 最终回答</li>
 *   <li>type=error      —— 出错</li>
 *   <li>type=complete   —— 完成标识</li>
 * </ul>
 *
 * @author ai-agent-station-study
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReActExecuteResultEntity {

    /** action / observation / final / error / complete */
    private String type;

    /** 子类型（action 时为工具名，如 run_bash / read_file / write_file） */
    private String subType;

    /** 内容 */
    private String content;

    /** 工具调用的步骤序号（从 1 递增） */
    private Integer step;

    /** 是否结束 */
    private Boolean completed;

    private Long timestamp;

    private String sessionId;

    public static ReActExecuteResultEntity createAction(int step, String toolName, String content, String sessionId) {
        return ReActExecuteResultEntity.builder()
                .type("action").subType(toolName).content(content).step(step)
                .completed(false).timestamp(System.currentTimeMillis()).sessionId(sessionId).build();
    }

    public static ReActExecuteResultEntity createObservation(int step, String toolName, String content, String sessionId) {
        return ReActExecuteResultEntity.builder()
                .type("observation").subType(toolName).content(content).step(step)
                .completed(false).timestamp(System.currentTimeMillis()).sessionId(sessionId).build();
    }

    public static ReActExecuteResultEntity createFinal(String content, String sessionId) {
        return ReActExecuteResultEntity.builder()
                .type("final").subType(null).content(content).step(null)
                .completed(true).timestamp(System.currentTimeMillis()).sessionId(sessionId).build();
    }

    public static ReActExecuteResultEntity createError(String content, String sessionId) {
        return ReActExecuteResultEntity.builder()
                .type("error").subType(null).content(content).step(null)
                .completed(true).timestamp(System.currentTimeMillis()).sessionId(sessionId).build();
    }

    public static ReActExecuteResultEntity createComplete(String sessionId) {
        return ReActExecuteResultEntity.builder()
                .type("complete").subType(null).content("执行完成").step(null)
                .completed(true).timestamp(System.currentTimeMillis()).sessionId(sessionId).build();
    }
}
