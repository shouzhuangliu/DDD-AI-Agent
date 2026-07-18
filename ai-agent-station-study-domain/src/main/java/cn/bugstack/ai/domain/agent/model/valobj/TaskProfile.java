package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * 任务画像 — 意图识别 + 任务分类的最终输出。
 * <p>
 * 4 个分类维度决定了执行策略:
 * - SIMPLE + 无需外部数据 → 快速通道(直答)
 * - 需外部数据 + 无依赖 → ReAct(工具循环)
 * - 需多步 + 有依赖 → Auto(完整链路)
 */
@Data @Builder
public class TaskProfile {

    /** 意图标签: GREETING / SIMPLE_QA / SEARCH / TASK / UNKNOWN */
    private String intent;

    // ========== 4 个分类维度 ==========

    /** 是否需要外部数据(文件/DB/API) */
    private boolean needsExternalData;

    /** 是否需要多步操作 */
    private boolean needsMultiStep;

    /** 输入复杂度: SIMPLE / MEDIUM / COMPLEX */
    private String inputComplexity;

    /** 步骤间是否有依赖关系 */
    private boolean hasDependencies;

    /** 建议执行模式: quick / react / auto */
    private String suggestedMode;

    /** 简要解释(仅供日志) */
    private String reason;

    public static TaskProfile quickReply(String intent, String reason) {
        return TaskProfile.builder().intent(intent).needsExternalData(false)
                .needsMultiStep(false).inputComplexity("SIMPLE")
                .hasDependencies(false).suggestedMode("quick").reason(reason).build();
    }

    public static TaskProfile reactMode(String intent, String reason) {
        return TaskProfile.builder().intent(intent).needsExternalData(true)
                .needsMultiStep(false).inputComplexity("MEDIUM")
                .hasDependencies(false).suggestedMode("react").reason(reason).build();
    }

    public static TaskProfile autoMode(String intent, String reason) {
        return TaskProfile.builder().intent(intent).needsExternalData(true)
                .needsMultiStep(true).inputComplexity("COMPLEX")
                .hasDependencies(true).suggestedMode("auto").reason(reason).build();
    }
}