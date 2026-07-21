package help.lixin.symphony.orchestrator;

import java.time.Instant;

/**
 * 运行中条目快照
 */
public record RunningSnapshot(
        String issueId,
        String identifier,
        String issueUrl,
        String state,
        String agentState,
        String codexProcessId,
        String workerHost,
        String workspacePath,
        String sessionId,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        int turnCount,
        Instant startedAt,
        Instant lastCodexTimestamp,
        String lastCodexEvent,
        String lastCodexMessage,
        long runtimeSeconds
) {}
