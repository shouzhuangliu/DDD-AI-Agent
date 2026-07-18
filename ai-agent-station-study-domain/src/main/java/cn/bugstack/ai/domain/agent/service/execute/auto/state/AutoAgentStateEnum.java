package cn.bugstack.ai.domain.agent.service.execute.auto.state;

import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Auto Agent 显式状态机枚举。
 * <p>
 * 集中定义所有状态及转移逻辑，取代原先分散在 Step1/Step3 get() 中的硬编码条件。
 * 每个节点执行完 doApply 后推进状态，get() 统一通过 {@link #nodeName()} 查找下一节点。
 * <p>
 * 状态图：
 * <pre>
 *   INTENT ──SIMPLE──→ SUMMARY
 *     │
 *     └──COMPLEX──→ ANALYZE ──completed──→ SUMMARY
 *                      │                     │
 *                      └──CONTINUE──→ EXECUTE → SUPERVISE ──PASS/超步──→ SUMMARY
 *                                                              │
 *                                                              └──FAIL──→ ANALYZE
 *   SUMMARY → END
 * </pre>
 *
 * @author ai-agent-station-study
 */
@Getter
@AllArgsConstructor
public enum AutoAgentStateEnum {

    /** 意图识别（入口，由 RootNode 设置） */
    INTENT("intent", null),
    /** 任务分析节点 */
    ANALYZE("analyze", "step1AnalyzerNode"),
    /** 精准执行节点 */
    EXECUTE("execute", "step2PrecisionExecutorNode"),
    /** 质量监督节点 */
    SUPERVISE("supervise", "step3QualitySupervisorNode"),
    /** 总结节点 */
    SUMMARY("summary", "step4LogExecutionSummaryNode"),
    /** 结束 */
    END("end", null);

    private final String code;
    private final String nodeName;

    /**
     * 根据当前状态与上下文计算下一状态。
     * 此方法集中体现了所有转移规则，新增/修改只需改这里。
     */
    public AutoAgentStateEnum next(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {
        return switch (this) {
            case INTENT -> {
                // 意图分类结果由 Step0 存入 context("intent")
                String intent = ctx.getValue("intent");
                if ("SIMPLE".equals(intent)) {
                    yield SUMMARY;
                }
                yield ANALYZE;
            }
            case ANALYZE -> {
                if (ctx.isCompleted()) {
                    yield SUMMARY;
                }
                yield EXECUTE;
            }
            case EXECUTE -> SUPERVISE;
            case SUPERVISE -> {
                if (ctx.isCompleted() || ctx.getStep() > ctx.getMaxStep()) {
                    yield SUMMARY;
                }
                yield ANALYZE;
            }
            case SUMMARY -> END;
            case END -> END;
        };
    }

    /**
     * 返回当前状态对应的 Spring Bean 名称（供 getBean 路由用）。
     * 与 {@link #getNodeName()} 等价，保留此别名使路由代码更自然。
     */
    public String nodeName() {
        return getNodeName();
    }

    /**
     * 根据状态名查找枚举。
     */
    public static AutoAgentStateEnum fromCode(String code) {
        if (code == null) return INTENT;
        for (AutoAgentStateEnum s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return INTENT;
    }
}