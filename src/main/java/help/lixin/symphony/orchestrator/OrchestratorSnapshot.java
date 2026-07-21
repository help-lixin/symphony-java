package help.lixin.symphony.orchestrator;

import java.util.List;

/**
 * 编排器快照 - 统一格式，供StatusDashboard使用
 */
public record OrchestratorSnapshot(
        List<RunningSnapshot> running,
        List<RetrySnapshot> retrying,
        List<BlockedSnapshot> blocked,
        CodexTotals codexTotals,
        PollingStatus polling,
        RateLimits rateLimits
) {}