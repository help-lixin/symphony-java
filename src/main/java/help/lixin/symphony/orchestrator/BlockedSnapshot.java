package help.lixin.symphony.orchestrator;

import java.time.Instant;

/**
 * 阻塞条目快照
 */
public record BlockedSnapshot(
        String issueId,
        String identifier,
        String issueUrl,
        String state,
        String workerHost,
        String workspacePath,
        String sessionId,
        String error,
        Instant blockedAt,
        Instant lastCodexTimestamp,
        String lastCodexEvent,
        String lastCodexMessage
) {}