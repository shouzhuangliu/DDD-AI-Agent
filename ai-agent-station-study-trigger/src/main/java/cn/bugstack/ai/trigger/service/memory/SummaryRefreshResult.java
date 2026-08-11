package cn.bugstack.ai.trigger.service.memory;

/** 后台滚动摘要刷新结果，供分析 worker 决定是否需要稍后重试。 */
public record SummaryRefreshResult(Status status) {

    public enum Status {
        SAVED,
        NOT_REQUIRED,
        LOCK_BUSY
    }
}
