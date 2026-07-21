package help.lixin.symphony.orchestrator;

/**
 * 重试条目快照
 */
public record RetrySnapshot(
        String issueId,
        int attempt,
        long dueInMs,
        String identifier,
        String issueUrl,
        String error,
        String workerHost,
        String workspacePath
) {}