package help.lixin.symphony.orchestrator;

import java.time.Instant;
import java.util.Map;

/**
 * Orchestrator消息协议
 *
 * 功能说明:
 * - 定义Orchestrator与Agent、Dashboard之间的消息类型
 * - 支持异步事件通知
 */
public class OrchestratorProtocol {

    private OrchestratorProtocol() {
        // 协议类不实例化
    }

    /**
     * Codex Worker更新消息
     *
     * @param issueId Issue ID
     * @param timestamp 时间戳
     * @param event 事件类型
     * @param payload 事件数据
     * @param sessionId 会话ID (可选)
     * @param inputTokens 输入token数 (可选)
     * @param outputTokens 输出token数 (可选)
     * @param totalTokens 总token数 (可选)
     * @param rateLimits 速率限制 (可选)
     */
    public record CodexWorkerUpdate(
            String issueId,
            Instant timestamp,
            String event,
            Map<String, Object> payload,
            String sessionId,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            RateLimits rateLimits
    ) {
        public CodexWorkerUpdate(String issueId, Instant timestamp, String event, Map<String, Object> payload) {
            this(issueId, timestamp, event, payload, null, null, null, null, null);
        }
    }

    /**
     * Worker运行时信息消息
     *
     * @param issueId Issue ID
     * @param workerHost Worker主机
     * @param workspacePath 工作区路径
     * @param codexProcessId Codex进程ID (可选)
     */
    public record WorkerRuntimeInfo(
            String issueId,
            String workerHost,
            String workspacePath,
            String codexProcessId
    ) {
        public WorkerRuntimeInfo(String issueId, String workerHost, String workspacePath) {
            this(issueId, workerHost, workspacePath, null);
        }
    }
/**
     * Agent Down消息
     *
     * @param issueId Issue ID
     * @param reason 退出原因
     */
    public record AgentDown(
            String issueId,
            String reason
    ) {}

    /**
     * 重试Issue消息
     *
     * @param issueId Issue ID
     */
    public record RetryIssue(
            String issueId
    ) {}

    /**
     * 快照请求
     */
    public record SnapshotRequest() {}

    /**
     * 刷新请求
     */
    public record RefreshRequest() {}

    /**
     * 运行时状态快照
     */
    public record RuntimeSnapshot(
            java.util.List<RunningSnapshot> running,
            java.util.List<RetrySnapshot> retrying,
            java.util.List<BlockedSnapshot> blocked,
            CodexTotals codexTotals,
            PollingStatus polling
    ) {}

    /**
     * 运行中Issue快照
     */
    public record RunningSnapshot(
            String issueId,
            String identifier,
            String issueUrl,
            String state,
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
            long runtimeSeconds
    ) {}

    /**
     * 重试中Issue快照
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

    /**
     * 阻塞Issue快照
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
            String lastCodexEvent
    ) {}

    /**
     * Codex使用统计
     */
    public record CodexTotals(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long secondsRunning
    ) {}

    /**
     * 轮询状态
     */
    public record PollingStatus(
            boolean checking,
            Long nextPollInMs,
            int pollIntervalMs
    ) {}
}
