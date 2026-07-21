package help.lixin.symphony.http;

import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.orchestrator.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * JSON API response formatter matching the Elixir Presenter module.
 */
public class ObservabilityApi {

    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    public static Map<String, Object> statePayload(OrchestratorSnapshot snapshot, AppConfig config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generated_at", ISO_FORMATTER.format(Instant.now()));

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("running", snapshot.running().size());
        counts.put("retrying", snapshot.retrying().size());
        counts.put("blocked", snapshot.blocked().size());
        payload.put("counts", counts);

        payload.put("running", snapshot.running().stream()
            .map(ObservabilityApi::runningEntryPayload)
            .toList());

        payload.put("retrying", snapshot.retrying().stream()
            .map(ObservabilityApi::retryEntryPayload)
            .toList());

        payload.put("blocked", snapshot.blocked().stream()
            .map(ObservabilityApi::blockedEntryPayload)
            .toList());

        payload.put("codex_totals", codexTotalsPayload(snapshot.codexTotals()));
        payload.put("rate_limits", rateLimitsPayload(snapshot.rateLimits()));

        return payload;
    }

    public static Optional<Map<String, Object>> issuePayload(String issueIdentifier,
                                                            OrchestratorSnapshot snapshot,
                                                            AppConfig config) {
        RunningSnapshot running = findByIdentifier(snapshot.running(), issueIdentifier);
        RetrySnapshot retry = findByIdentifier(snapshot.retrying(), issueIdentifier);
        BlockedSnapshot blocked = findByIdentifier(snapshot.blocked(), issueIdentifier);

        if (running == null && retry == null && blocked == null) {
            return Optional.empty();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issue_identifier", issueIdentifier);
        payload.put("issue_id", resolveIssueId(running, retry, blocked));
        payload.put("status", resolveStatus(running, retry, blocked));

        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("path", resolveWorkspacePath(issueIdentifier, running, retry, blocked, config));
        workspace.put("host", resolveWorkspaceHost(running, retry, blocked));
        payload.put("workspace", workspace);

        Map<String, Object> attempts = new LinkedHashMap<>();
        attempts.put("restart_count", retry != null ? Math.max(retry.attempt() - 1, 0) : 0);
        attempts.put("current_retry_attempt", retry != null ? retry.attempt() : 0);
        payload.put("attempts", attempts);

        payload.put("running", running != null ? runningIssuePayload(running) : null);
        payload.put("retry", retry != null ? retryIssuePayload(retry) : null);
        payload.put("blocked", blocked != null ? blockedIssuePayload(blocked) : null);

        Map<String, Object> logs = new LinkedHashMap<>();
        logs.put("codex_session_logs", Collections.emptyList());
        payload.put("logs", logs);

        payload.put("recent_events", recentEventsPayload(running != null ? running : blocked));
        payload.put("last_error", blocked != null ? blocked.error() : (retry != null ? retry.error() : null));
        payload.put("tracked", Collections.emptyMap());

        return Optional.of(payload);
    }

    public static Map<String, Object> refreshPayload(Orchestrator.RefreshResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requested_at", ISO_FORMATTER.format(Instant.now()));
        payload.put("queued", result.queued());
        payload.put("coalesced", result.coalesced());
        return payload;
    }

    private static Map<String, Object> runningEntryPayload(RunningSnapshot entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issue_id", entry.issueId());
        payload.put("issue_identifier", entry.identifier());
        payload.put("issue_url", entry.issueUrl());
        payload.put("state", entry.state());
        payload.put("worker_host", entry.workerHost());
        payload.put("workspace_path", entry.workspacePath());
        payload.put("session_id", entry.sessionId());
        payload.put("turn_count", entry.turnCount());
        payload.put("last_event", entry.lastCodexEvent());
        payload.put("last_message", entry.lastCodexMessage());
        payload.put("started_at", entry.startedAt() != null ? ISO_FORMATTER.format(entry.startedAt()) : null);
        payload.put("last_event_at", entry.lastCodexTimestamp() != null ? ISO_FORMATTER.format(entry.lastCodexTimestamp()) : null);

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input_tokens", entry.inputTokens());
        tokens.put("output_tokens", entry.outputTokens());
        tokens.put("total_tokens", entry.totalTokens());
        payload.put("tokens", tokens);

        return payload;
    }

    private static Map<String, Object> retryEntryPayload(RetrySnapshot entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issue_id", entry.issueId());
        payload.put("issue_identifier", entry.identifier());
        payload.put("issue_url", entry.issueUrl());
        payload.put("attempt", entry.attempt());
        payload.put("due_at", dueAtIso8601(entry.dueInMs()));
        payload.put("error", entry.error());
        payload.put("worker_host", entry.workerHost());
        payload.put("workspace_path", entry.workspacePath());
        return payload;
    }

    private static Map<String, Object> blockedEntryPayload(BlockedSnapshot entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issue_id", entry.issueId());
        payload.put("issue_identifier", entry.identifier());
        payload.put("issue_url", entry.issueUrl());
        payload.put("state", entry.state());
        payload.put("error", entry.error());
        payload.put("worker_host", entry.workerHost());
        payload.put("workspace_path", entry.workspacePath());
        payload.put("session_id", entry.sessionId());
        payload.put("blocked_at", entry.blockedAt() != null ? ISO_FORMATTER.format(entry.blockedAt()) : null);
        payload.put("last_event", entry.lastCodexEvent());
        payload.put("last_message", entry.lastCodexMessage());
        payload.put("last_event_at", entry.lastCodexTimestamp() != null ? ISO_FORMATTER.format(entry.lastCodexTimestamp()) : null);
        return payload;
    }

    private static Map<String, Object> runningIssuePayload(RunningSnapshot running) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("worker_host", running.workerHost());
        payload.put("workspace_path", running.workspacePath());
        payload.put("session_id", running.sessionId());
        payload.put("turn_count", running.turnCount());
        payload.put("state", running.state());
        payload.put("started_at", running.startedAt() != null ? ISO_FORMATTER.format(running.startedAt()) : null);
        payload.put("last_event", running.lastCodexEvent());
        payload.put("last_message", running.lastCodexMessage());
        payload.put("last_event_at", running.lastCodexTimestamp() != null ? ISO_FORMATTER.format(running.lastCodexTimestamp()) : null);

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input_tokens", running.inputTokens());
        tokens.put("output_tokens", running.outputTokens());
        tokens.put("total_tokens", running.totalTokens());
        payload.put("tokens", tokens);

        return payload;
    }

    private static Map<String, Object> retryIssuePayload(RetrySnapshot retry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt", retry.attempt());
        payload.put("due_at", dueAtIso8601(retry.dueInMs()));
        payload.put("error", retry.error());
        payload.put("worker_host", retry.workerHost());
        payload.put("workspace_path", retry.workspacePath());
        return payload;
    }

    private static Map<String, Object> blockedIssuePayload(BlockedSnapshot blocked) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("worker_host", blocked.workerHost());
        payload.put("workspace_path", blocked.workspacePath());
        payload.put("session_id", blocked.sessionId());
        payload.put("state", blocked.state());
        payload.put("error", blocked.error());
        payload.put("blocked_at", blocked.blockedAt() != null ? ISO_FORMATTER.format(blocked.blockedAt()) : null);
        payload.put("last_event", blocked.lastCodexEvent());
        payload.put("last_message", blocked.lastCodexMessage());
        payload.put("last_event_at", blocked.lastCodexTimestamp() != null ? ISO_FORMATTER.format(blocked.lastCodexTimestamp()) : null);
        return payload;
    }

    private static Map<String, Object> codexTotalsPayload(CodexTotals totals) {
        if (totals == null) {
            return Map.of(
                "input_tokens", 0L,
                "output_tokens", 0L,
                "total_tokens", 0L,
                "seconds_running", 0L
            );
        }
        return Map.of(
            "input_tokens", totals.inputTokens(),
            "output_tokens", totals.outputTokens(),
            "total_tokens", totals.totalTokens(),
            "seconds_running", totals.secondsRunning()
        );
    }

    private static Object rateLimitsPayload(RateLimits rateLimits) {
        if (rateLimits == null) {
            return Collections.emptyMap();
        }
        return rateLimits;
    }

    private static <T> T findByIdentifier(List<T> list, String identifier) {
        if (list == null) return null;
        for (T item : list) {
            String id = getIdentifier(item);
            if (identifier.equals(id)) {
                return item;
            }
        }
        return null;
    }

    private static String getIdentifier(Object item) {
        if (item instanceof RunningSnapshot r) return r.identifier();
        if (item instanceof RetrySnapshot r) return r.identifier();
        if (item instanceof BlockedSnapshot b) return b.identifier();
        return null;
    }

    private static String resolveIssueId(RunningSnapshot running, RetrySnapshot retry, BlockedSnapshot blocked) {
        if (running != null) return running.issueId();
        if (retry != null) return retry.issueId();
        if (blocked != null) return blocked.issueId();
        return null;
    }

    private static String resolveStatus(RunningSnapshot running, RetrySnapshot retry, BlockedSnapshot blocked) {
        if (running != null) return "running";
        if (retry != null) return "retrying";
        return "blocked";
    }

    private static String resolveWorkspacePath(String issueIdentifier, RunningSnapshot running,
                                              RetrySnapshot retry, BlockedSnapshot blocked, AppConfig config) {
        if (running != null && running.workspacePath() != null) return running.workspacePath();
        if (retry != null && retry.workspacePath() != null) return retry.workspacePath();
        if (blocked != null && blocked.workspacePath() != null) return blocked.workspacePath();
        String root = config.getWorkspace().getRoot();
        return root != null ? root + "/" + issueIdentifier : issueIdentifier;
    }

    private static String resolveWorkspaceHost(RunningSnapshot running, RetrySnapshot retry, BlockedSnapshot blocked) {
        if (running != null) return running.workerHost();
        if (retry != null) return retry.workerHost();
        if (blocked != null) return blocked.workerHost();
        return null;
    }

    private static String dueAtIso8601(long dueInMs) {
        if (dueInMs <= 0) return null;
        Instant dueAt = Instant.now().plus(dueInMs, ChronoUnit.MILLIS);
        return ISO_FORMATTER.format(dueAt.truncatedTo(ChronoUnit.SECONDS));
    }

    private static List<Map<String, Object>> recentEventsPayload(Object entry) {
        if (entry == null) return Collections.emptyList();

        String lastEvent = null;
        String lastMessage = null;
        Instant lastTimestamp = null;

        if (entry instanceof RunningSnapshot r) {
            lastEvent = r.lastCodexEvent();
            lastMessage = r.lastCodexMessage();
            lastTimestamp = r.lastCodexTimestamp();
        } else if (entry instanceof BlockedSnapshot b) {
            lastEvent = b.lastCodexEvent();
            lastMessage = b.lastCodexMessage();
            lastTimestamp = b.lastCodexTimestamp();
        }

        if (lastTimestamp == null) return Collections.emptyList();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", ISO_FORMATTER.format(lastTimestamp.truncatedTo(ChronoUnit.SECONDS)));
        event.put("event", lastEvent);
        event.put("message", lastMessage);

        return List.of(event);
    }
}
