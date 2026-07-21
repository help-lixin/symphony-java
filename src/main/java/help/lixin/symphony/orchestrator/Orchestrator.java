package help.lixin.symphony.orchestrator;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import help.lixin.symphony.agent.AgentRunnerTyped;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.dashboard.StatusDashboard;
import help.lixin.symphony.model.Issue;
import help.lixin.symphony.tracker.Tracker;
import help.lixin.symphony.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 核心编排器Actor (Akka Typed)
 */
public class Orchestrator extends AbstractBehavior<Orchestrator.Command> {

    private static final Logger logger = LoggerFactory.getLogger(Orchestrator.class);

    private static final int CONTINUATION_RETRY_DELAY_MS = 1_000;
    private static final int FAILURE_RETRY_BASE_MS = 10_000;

    private final AppConfig config;
    private final Tracker tracker;
    private final WorkspaceManager workspaceManager;
    private final int pollIntervalMs;
    private final int maxConcurrentAgents;
    private ActorRef<StatusDashboard.Command> dashboardRef;

    private final Map<String, RunningEntry> running = new HashMap<>();
    private final Set<String> completed = new HashSet<>();
    private final Set<String> claimed = new HashSet<>();
    private final Map<String, BlockedEntry> blocked = new HashMap<>();
    private final Map<String, RetryEntry> retryAttempts = new HashMap<>();

    private long codexInputTokens = 0;
    private long codexOutputTokens = 0;
    private long codexTotalTokens = 0;
    private long secondsRunning = 0;
    private Instant startTime;
    private Instant nextPollDueAt;
    private boolean pollInProgress;
    private RateLimits rateLimits;

    private final String POLL_TIMER_KEY = "poll-timer";
    private final String POLLCYCLE_TIMER_KEY = "pollcycle-timer";
    private final String RETRY_TIMER_PREFIX = "retry-";

    private final TimerScheduler<Command> timers;

    private Orchestrator(ActorContext<Command> context,
                        TimerScheduler<Command> timers,
                        AppConfig config,
                        Tracker tracker,
                        WorkspaceManager workspaceManager,
                        ActorRef<StatusDashboard.Command> dashboardRef) {
        super(context);
        this.timers = timers;
        this.config = config;
        this.tracker = tracker;
        this.workspaceManager = workspaceManager;
        this.pollIntervalMs = config.getPolling().getIntervalMs();
        this.maxConcurrentAgents = config.getAgent().getMaxConcurrentAgents();
        this.dashboardRef = dashboardRef;
        this.startTime = Instant.now();
    }

    public static Behavior<Command> create(AppConfig config, Tracker tracker,
                                          WorkspaceManager workspaceManager,
                                          ActorRef<StatusDashboard.Command> dashboardRef) {
        return Behaviors.withTimers(timers -> Behaviors.setup(ctx ->
                new Orchestrator(ctx, timers, config, tracker, workspaceManager, dashboardRef).start()));
    }

    private Orchestrator start() {
        logger.info("Orchestrator启动");
        runTerminalWorkspaceCleanup();
        return this;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, msg -> handleStart())
                .onMessage(Tick.class, msg -> handleTick())
                .onMessage(PollCycle.class, msg -> handlePollCycle())
                .onMessage(Snapshot.class, this::handleSnapshot)
                .onMessage(RequestRefresh.class, msg -> handleRequestRefresh())
                .onMessage(CodexUpdate.class, msg -> handleCodexUpdate(msg))
                .onMessage(WorkerRuntimeInfo.class, msg -> handleWorkerRuntimeInfo(msg))
                .onMessage(AgentDown.class, msg -> handleAgentDown(msg))
                .onMessage(RetryIssue.class, msg -> handleRetryIssue(msg))
                .onMessage(RegisterDashboard.class, msg -> handleRegisterDashboard(msg))
                .onMessage(DispatchIssue.class, msg -> handleDispatchIssue(msg))
                .build();
    }

    private Behavior<Command> handleStart() {
        logger.info("Orchestrator开始轮询");
        scheduleNextPoll(0);
        return this;
    }

    private Behavior<Command> handleTick() {
        if (pollInProgress) return this;
        pollInProgress = true;
        nextPollDueAt = null;
        updateSecondsRunning();
        notifyDashboard();
        schedulePollCycle();
        return this;
    }

    private void updateSecondsRunning() {
        if (startTime != null) {
            secondsRunning = Duration.between(startTime, Instant.now()).getSeconds();
        }
    }

    private Behavior<Command> handlePollCycle() {
        try {
            reconcileRunningIssues();
            reconcileBlockedIssues();
            reconcileRetryAttempts();

            if (!validateConfig()) {
                pollInProgress = false;
                return this;
            }

            tracker.fetchCandidateIssues()
                    .thenAccept(issues -> {
                        if (availableSlots() > 0) chooseAndDispatch(issues);
                        scheduleNextPoll(pollIntervalMs);
                        pollInProgress = false;
                        notifyDashboard();
                    })
                    .exceptionally(ex -> {
                        logger.error("轮询失败", ex);
                        scheduleNextPoll(pollIntervalMs);
                        pollInProgress = false;
                        notifyDashboard();
                        return null;
                    });
        } catch (Exception e) {
            logger.error("处理轮询周期失败", e);
            pollInProgress = false;
            scheduleNextPoll(pollIntervalMs);
        }
        return this;
    }

    private Behavior<Command> handleCodexUpdate(CodexUpdate update) {
        RunningEntry entry = running.get(update.issueId());
        if (entry != null) {
            entry.setLastCodexTimestamp(update.timestamp());
            entry.setLastCodexEvent(update.event());

            // Extract human-readable message from payload
            String message = extractMessageFromPayload(update.payload());
            entry.setLastCodexMessage(message);

            if (update.sessionId() != null && !update.sessionId().isBlank()) {
                String prevSessionId = entry.getSessionId();
                if (prevSessionId == null || !prevSessionId.equals(update.sessionId())) {
                    entry.setSessionId(update.sessionId());
                    entry.setTurnCount(entry.getTurnCount() + 1);
                }
            }

            if (update.inputTokens() != null) {
                long delta = update.inputTokens() - entry.getLastReportedInputTokens();
                if (delta > 0) {
                    entry.addInputTokens(delta);
                    entry.setLastReportedInputTokens(update.inputTokens());
                    codexInputTokens += delta;
                }
            }
            if (update.outputTokens() != null) {
                long delta = update.outputTokens() - entry.getLastReportedOutputTokens();
                if (delta > 0) {
                    entry.addOutputTokens(delta);
                    entry.setLastReportedOutputTokens(update.outputTokens());
                    codexOutputTokens += delta;
                }
            }
            if (update.totalTokens() != null) {
                long delta = update.totalTokens() - entry.getLastReportedTotalTokens();
                if (delta > 0) {
                    entry.addTotalTokens(delta);
                    entry.setLastReportedTotalTokens(update.totalTokens());
                    codexTotalTokens += delta;
                }
            }

            if (update.rateLimits() != null) {
                this.rateLimits = update.rateLimits();
            }

            notifyDashboard();
        }
        return this;
    }

    private Behavior<Command> handleWorkerRuntimeInfo(WorkerRuntimeInfo info) {
        RunningEntry entry = running.get(info.issueId());
        if (entry != null) {
            entry.setWorkerHost(info.workerHost());
            entry.setWorkspacePath(info.workspacePath());
            if (info.codexProcessId() != null) {
                entry.setCodexProcessId(info.codexProcessId());
            }
            notifyDashboard();
        }
        return this;
    }

    private Behavior<Command> handleAgentDown(AgentDown msg) {
        RunningEntry entry = running.remove(msg.issueId());
        if (entry != null) {
            claimed.remove(msg.issueId());

            if (isInputRequiredBlocker(entry)) {
                blockIssue(msg.issueId(), entry, "agent exited");
            } else if (msg.reason() == null) {
                // Normal exit: schedule continuation retry (1s delay, matching Elixir handle_agent_down(:normal))
                completed.add(msg.issueId());
                scheduleRetry(msg.issueId(), entry, 1, Map.of(
                        "identifier", entry.getIdentifier(),
                        "issue_url", entry.getIssue().getUrl(),
                        "error", "agent exited",
                        "delay_type", "continuation",
                        "worker_host", entry.getWorkerHost() != null ? entry.getWorkerHost() : "",
                        "workspace_path", entry.getWorkspacePath() != null ? entry.getWorkspacePath() : ""
                ));
            } else {
                // Error exit: schedule failure retry with exponential backoff (matching Elixir handle_agent_down(reason))
                logger.warn("Agent exited with error: issue={}, reason={}", entry.getIdentifier(), msg.reason());
                completed.add(msg.issueId());
                scheduleRetry(msg.issueId(), entry, 1, Map.of(
                        "identifier", entry.getIdentifier(),
                        "issue_url", entry.getIssue().getUrl(),
                        "error", "agent exited: " + msg.reason(),
                        "worker_host", entry.getWorkerHost() != null ? entry.getWorkerHost() : "",
                        "workspace_path", entry.getWorkspacePath() != null ? entry.getWorkspacePath() : ""
                ));
            }
            notifyDashboard();
        }
        return this;
    }

    private Behavior<Command> handleRetryIssue(RetryIssue msg) {
        // Use get() instead of remove() to keep the guard entry in place
        // during the async fetch gap, preventing duplicate dispatch
        RetryEntry retryEntry = retryAttempts.get(msg.issueId());
        if (retryEntry == null) return this;

        tracker.fetchCandidateIssues()
                .thenAccept(issues -> {
                    // Safe to remove now - async fetch has completed
                    retryAttempts.remove(msg.issueId());

                    Optional<Issue> issueOpt = issues.stream().filter(i -> i.getId().equals(msg.issueId())).findFirst();
                    if (issueOpt.isPresent()) {
                        Issue issue = issueOpt.get();
                        if (isTerminalState(issue.getState())) {
                            // Issue reached terminal state (Done/Closed) - mark as completed and cleanup
                            logger.info("Issue reached terminal state, completing: issue={}, state={}",
                                    issue.getIdentifier(), issue.getState());
                            completed.add(msg.issueId());
                            claimed.remove(msg.issueId());
                            cleanupWorkspace(retryEntry.getWorkspacePath(), retryEntry.getWorkerHost());
                        } else if (retryCandidateIssue(issue) && availableSlots() > 0) {
                            getContext().getSelf().tell(new DispatchIssue(issue, retryEntry.getAttempt(), retryEntry.getWorkerHost()));
                        } else {
                            claimed.remove(msg.issueId());
                        }
                    } else {
                        claimed.remove(msg.issueId());
                    }
                    notifyDashboard();
                });
        return this;
    }

    private Behavior<Command> handleSnapshot(Snapshot msg) {
        OrchestratorSnapshot snapshot = buildSnapshot();
        dashboardRef.tell(new StatusDashboard.SnapshotResult(snapshot));
        msg.replyTo().tell(snapshot);
        return Behaviors.same();
    }

    private Behavior<Command> handleRequestRefresh() {
        if (pollInProgress) {
            getContext().getSelf().tell(new RefreshResult(true, true));
        } else {
            scheduleNextPoll(0);
            getContext().getSelf().tell(new RefreshResult(true, false));
        }
        return this;
    }

    private Behavior<Command> handleRegisterDashboard(RegisterDashboard msg) {
        this.dashboardRef = msg.dashboardRef();
        logger.info("Dashboard已注册");
        // Send initial tick to trigger first render
        if (dashboardRef != null) {
            dashboardRef.tell(StatusDashboard.Tick.INSTANCE);
        }
        return this;
    }

    private Behavior<Command> handleDispatchIssue(DispatchIssue msg) {
        Issue issue = msg.issue();
        Integer attempt = msg.attempt();
        String preferredWorkerHost = msg.preferredWorkerHost();
        doDispatchIssue(issue, attempt, preferredWorkerHost);
        return this;
    }

    // Sync versions for internal use
    private Behavior<Command> handleCodexUpdateSync(RunningEntry entry, CodexUpdate update) {
        entry.setLastCodexTimestamp(update.timestamp());
        entry.setLastCodexEvent(update.event());
        if (update.inputTokens() != null) {
            long delta = update.inputTokens() - entry.getLastReportedInputTokens();
            if (delta > 0) {
                entry.addInputTokens(delta);
                entry.setLastReportedInputTokens(update.inputTokens());
                codexInputTokens += delta;
            }
        }
        // ... similar for other fields
        notifyDashboard();
        return this;
    }

    private void reconcileRunningIssues() {
        if (running.isEmpty()) return;
        List<String> runningIds = new ArrayList<>(running.keySet());
        tracker.fetchIssueStatesByIds(runningIds)
                .thenAccept(issues -> {
                    for (Issue issue : issues) {
                        if (isTerminalState(issue.getState())) {
                            logger.info("Issue进入终态，停止Agent: issue={}, state={}",
                                    issue.getIdentifier(), issue.getState());
                            terminateRunningIssue(issue.getId(), true);
                        } else if (!isActiveState(issue.getState())) {
                            logger.info("Issue离开活跃状态，停止Agent: issue={}, state={}",
                                    issue.getIdentifier(), issue.getState());
                            terminateRunningIssue(issue.getId(), false);
                        }
                    }
                });
    }

    private void reconcileBlockedIssues() {
        if (blocked.isEmpty()) return;
        List<String> blockedIds = new ArrayList<>(blocked.keySet());
        tracker.fetchIssueStatesByIds(blockedIds)
                .thenAccept(issues -> {
                    for (Issue issue : issues) {
                        if (isTerminalState(issue.getState())) {
                            logger.info("阻塞Issue进入终态，释放: issue={}", issue.getIdentifier());
                            releaseBlockedIssue(issue.getId());
                        }
                    }
                });
    }

    private void reconcileRetryAttempts() {
        if (retryAttempts.isEmpty()) return;
        List<String> retryIds = new ArrayList<>(retryAttempts.keySet());
        tracker.fetchIssueStatesByIds(retryIds)
                .thenAccept(issues -> {
                    for (Issue issue : issues) {
                        if (isTerminalState(issue.getState())) {
                            logger.info("Retry中的Issue进入终态，释放: issue={}", issue.getIdentifier());
                            retryAttempts.remove(issue.getId());
                            claimed.remove(issue.getId());
                        }
                    }
                });
    }

    private void chooseAndDispatch(List<Issue> issues) {
        List<Issue> sorted = sortIssuesForDispatch(issues);
        for (Issue issue : sorted) {
            if (availableSlots() <= 0) break;
            if (shouldDispatch(issue)) {
                getContext().getSelf().tell(new DispatchIssue(issue, null, null));
            }
        }
    }

    private void doDispatchIssue(Issue issue, Integer attempt, String preferredWorkerHost) {
        String issueId = issue.getId();
        if (claimed.contains(issueId) || running.containsKey(issueId) || retryAttempts.containsKey(issueId)) return;

        claimed.add(issueId);
        RunningEntry entry = new RunningEntry(
                issue.getId(), issue.getIdentifier(), issue,
                preferredWorkerHost, null, null, null, null, Instant.now());
        running.put(issueId, entry);

        String actorName = "agent-" + issueId + "-" + UUID.randomUUID().toString();
        try {
            ActorRef<AgentRunnerTyped.Command> agentRef = getContext().spawn(
                    AgentRunnerTyped.create(config, tracker, workspaceManager, getContext().getSelf()),
                    actorName);
            agentRef.tell(new AgentRunnerTyped.StartAgent(issue, preferredWorkerHost));

            logger.info("调度Issue: issue={}, worker={}",
                    issue.getIdentifier(), preferredWorkerHost != null ? preferredWorkerHost : "local");
        } catch (Exception e) {
            // Actor name collision - previous agent with same issue ID is still terminating
            logger.warn("无法调度Issue (actor名冲突): issue={}, error={}", issue.getIdentifier(), e.getMessage());
            running.remove(issueId);
            claimed.remove(issueId);
        }
    }

    private void terminateRunningIssue(String issueId, boolean cleanupWorkspace) {
        RunningEntry entry = running.remove(issueId);
        if (entry != null) {
            claimed.remove(issueId);
            if (cleanupWorkspace) {
                cleanupWorkspace(entry.getWorkspacePath(), entry.getWorkerHost());
            }
        }
    }

    private void cleanupWorkspace(String workspacePath, String workerHost) {
        if (workspacePath != null && !workspacePath.isBlank()) {
            try {
                workspaceManager.remove(Path.of(workspacePath), workerHost);
            } catch (Exception e) {
                logger.warn("清理工作区失败: workspace={}", workspacePath, e);
            }
        }
    }

    private Behavior<Command> blockIssue(String issueId, RunningEntry entry, String error) {
        running.remove(issueId);
        claimed.add(issueId);
        BlockedEntry blockedEntry = new BlockedEntry(
                issueId, entry.getIdentifier(), entry.getIssue(),
                entry.getWorkerHost(), entry.getWorkspacePath(), error, entry.getSessionId(), Instant.now());
        blockedEntry.setLastCodexEvent(entry.getLastCodexEvent());
        blockedEntry.setLastCodexMessage(entry.getLastCodexMessage());
        blockedEntry.setLastCodexTimestamp(entry.getLastCodexTimestamp());
        blocked.put(issueId, blockedEntry);
        logger.info("Issue被阻塞: issue={}, error={}", entry.getIdentifier(), error);
        return this;
    }

    private void releaseBlockedIssue(String issueId) {
        blocked.remove(issueId);
        claimed.remove(issueId);
    }

    private void scheduleRetry(String issueId, RunningEntry entry, int attempt, Map<String, Object> metadata) {
        int delayMs = calculateRetryDelay(attempt, metadata);
        RetryEntry retryEntry = new RetryEntry(
                issueId, attempt,
                (String) metadata.get("identifier"),
                (String) metadata.get("issue_url"),
                (String) metadata.get("error"),
                (String) metadata.get("worker_host"),
                (String) metadata.get("workspace_path"),
                Instant.now().plusMillis(delayMs));
        retryAttempts.put(issueId, retryEntry);

        timers.startSingleTimer(
                RETRY_TIMER_PREFIX + issueId,
                new RetryIssue(issueId),
                Duration.ofMillis(delayMs));

        logger.info("调度重试: issue={}, attempt={}, delay={}ms", entry.getIdentifier(), attempt, delayMs);
    }

    private boolean validateConfig() {
        if (config.getTracker().getApiKey() == null) {
            logger.error("Linear API token未设置");
            return false;
        }
        if (config.getTracker().getProjectSlug() == null) {
            logger.error("Linear project slug未设置");
            return false;
        }
        return true;
    }

    private int availableSlots() {
        return maxConcurrentAgents - running.size();
    }

    private boolean shouldDispatch(Issue issue) {
        if (claimed.contains(issue.getId())) return false;
        if (running.containsKey(issue.getId())) return false;
        if (blocked.containsKey(issue.getId())) return false;
        if (retryAttempts.containsKey(issue.getId())) return false;
        if (!issue.isAssignedToWorker()) return false;
        if (!issue.isRoutable(config.getTracker().getRequiredLabels())) return false;
        return isActiveState(issue.getState());
    }

    private List<Issue> sortIssuesForDispatch(List<Issue> issues) {
        return issues.stream()
                .sorted(Comparator.comparing((Issue i) -> priorityRank(i.getPriority()))
                        .thenComparing(i -> createdAtSortKey(i)))
                .toList();
    }

    private int priorityRank(Integer priority) {
        if (priority != null && priority >= 1 && priority <= 4) return priority;
        return 5;
    }

    private long createdAtSortKey(Issue issue) {
        if (issue.getCreatedAt() != null) return issue.getCreatedAt().toEpochMilli();
        return Long.MAX_VALUE;
    }

    private boolean isActiveState(String state) {
        if (state == null) return false;
        String normalized = state.toLowerCase().trim();
        return config.getTracker().getActiveStates().stream()
                .map(String::toLowerCase).map(String::trim)
                .anyMatch(s -> s.equals(normalized));
    }

    private boolean isTerminalState(String state) {
        if (state == null) return false;
        String normalized = state.toLowerCase().trim();
        return config.getTracker().getTerminalStates().stream()
                .map(String::toLowerCase).map(String::trim)
                .anyMatch(s -> s.equals(normalized));
    }

    /**
     * Check if an issue is a valid candidate for retry dispatch.
     * Matches Elixir's retry_candidate_issue?: active state + routable.
     */
    private boolean retryCandidateIssue(Issue issue) {
        return isActiveState(issue.getState())
            && issue.isRoutable(config.getTracker().getRequiredLabels());
    }

    private int calculateRetryDelay(int attempt, Map<String, Object> metadata) {
        if (attempt == 1 && "continuation".equals(metadata.get("delay_type"))) {
            return CONTINUATION_RETRY_DELAY_MS;
        }
        int maxDelayPower = Math.min(attempt - 1, 10);
        int delay = FAILURE_RETRY_BASE_MS * (1 << maxDelayPower);
        return Math.min(delay, config.getAgent().getMaxRetryBackoffMs());
    }

    private boolean isInputRequiredBlocker(RunningEntry entry) {
        return entry.getLastCodexEvent() != null &&
                (entry.getLastCodexEvent().contains("input_required") ||
                        entry.getLastCodexEvent().contains("approval_required"));
    }

    @SuppressWarnings("unchecked")
    private String extractMessageFromPayload(Map<String, Object> payload) {
        if (payload == null) return null;

        // Try to extract method from payload
        String method = getStringField(payload, "method");
        if (method != null) {
            return humanizeCodexMethod(method, payload);
        }

        // Try to extract session_id
        String sessionId = getStringField(payload, "session_id");
        if (sessionId != null) {
            return "session started (" + sessionId + ")";
        }

        // Try to extract reason (for errors)
        String reason = getStringField(payload, "reason");
        if (reason != null) {
            return "error: " + reason;
        }

        // Try to extract error from payload
        Object error = payload.get("error");
        if (error instanceof Map) {
            Map<String, Object> errorMap = (Map<String, Object>) error;
            Object errorMessage = errorMap.get("message");
            if (errorMessage != null) {
                return "error: " + errorMessage.toString();
            }
        }

        // Fallback: return the payload as a string (truncated)
        String payloadStr = payload.toString();
        if (payloadStr.length() > 140) {
            return payloadStr.substring(0, 137) + "...";
        }
        return payloadStr;
    }

    private String humanizeCodexMethod(String method, Map<String, Object> payload) {
        if (method == null) return null;

        // Handle common Codex methods
        if (method.contains("session_started")) {
            String sessionId = getStringField(payload, "session_id");
            if (sessionId != null) return "session started (" + sessionId + ")";
            return "session started";
        }
        if (method.contains("turn_input_required") || method.contains("input_required")) {
            return "turn blocked: waiting for user input";
        }
        if (method.contains("approval_auto_approved")) {
            String decision = getStringField(payload, "decision");
            String toolMethod = getStringField(payload, "method");
            if (toolMethod != null) {
                String base = humanizeCodexMethod(toolMethod, payload) + " (auto-approved)";
                if (decision != null) return base + ": " + decision;
                return base;
            }
            return "approval request auto-approved";
        }
        if (method.contains("tool_input_auto_answered")) {
            String answer = getStringField(payload, "answer");
            String toolMethod = getStringField(payload, "method");
            if (toolMethod != null) {
                String base = humanizeCodexMethod(toolMethod, payload) + " (auto-answered)";
                if (answer != null) return base + ": " + truncate(answer, 100);
                return base;
            }
            return "tool input auto-answered";
        }
        if (method.contains("tool_call_completed")) {
            String tool = getStringField(payload, "tool");
            if (tool != null) return "dynamic tool call completed (" + tool + ")";
            return "dynamic tool call completed";
        }
        if (method.contains("tool_call_failed")) {
            String tool = getStringField(payload, "tool");
            if (tool != null) return "dynamic tool call failed (" + tool + ")";
            return "dynamic tool call failed";
        }
        if (method.contains("unsupported_tool_call")) {
            String tool = getStringField(payload, "tool");
            if (tool != null) return "unsupported dynamic tool call rejected (" + tool + ")";
            return "unsupported dynamic tool call rejected";
        }
        if (method.contains("turn_ended_with_error")) {
            return "turn ended with error";
        }
        if (method.contains("startup_failed")) {
            return "startup failed";
        }
        if (method.contains("turn_cancelled")) {
            return "turn cancelled";
        }

        // Default: return method name cleaned up
        return method.replace("codex/event/", "").replace("_", " ");
    }

    private String getStringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) value = map.get(key.replace("_", ""));
        if (value == null) value = map.get(key.replace("-", ""));
        if (value instanceof String) return (String) value;
        if (value != null) return value.toString();
        return null;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 3) + "...";
    }

    private void runTerminalWorkspaceCleanup() {
        tracker.fetchIssuesByStates(config.getTracker().getTerminalStates())
                .thenAccept(issues -> {
                    for (Issue issue : issues) {
                        if (issue.getIdentifier() != null) {
                            try {
                                workspaceManager.remove(
                                        Path.of(config.getWorkspace().getRoot(), safeIdentifier(issue.getIdentifier())),
                                        null);
                            } catch (Exception e) {
                                logger.warn("清理工作区失败: {}", issue.getIdentifier(), e);
                            }
                        }
                    }
                });
    }

    private String safeIdentifier(String identifier) {
        if (identifier == null) return "issue";
        return identifier.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void scheduleNextPoll(long delayMs) {
        timers.startSingleTimer(
                POLL_TIMER_KEY, Tick.INSTANCE, Duration.ofMillis(delayMs));
        nextPollDueAt = Instant.now().plusMillis(delayMs);
    }

    private void schedulePollCycle() {
        timers.startSingleTimer(
                POLLCYCLE_TIMER_KEY, PollCycle.INSTANCE, Duration.ofMillis(20));
    }

    private void notifyDashboard() {
        if (dashboardRef != null) {
            dashboardRef.tell(StatusDashboard.Tick.INSTANCE);
        }
    }

    private OrchestratorSnapshot buildSnapshot() {
        Instant now = Instant.now();
        updateSecondsRunning();

        List<RunningSnapshot> runningSnapshots = running.values().stream()
                .map(entry -> new RunningSnapshot(
                        entry.getIssueId(), entry.getIdentifier(),
                        entry.getIssue() != null ? entry.getIssue().getUrl() : null,
                        entry.getIssue() != null ? entry.getIssue().getState() : null,
                        entry.getAgentState(), entry.getCodexProcessId(),
                        entry.getWorkerHost(), entry.getWorkspacePath(),
                        entry.getSessionId(), entry.getInputTokens(),
                        entry.getOutputTokens(), entry.getTotalTokens(),
                        entry.getTurnCount(), entry.getStartedAt(),
                        entry.getLastCodexTimestamp(), entry.getLastCodexEvent(),
                        entry.getLastCodexMessage(),
                        entry.getStartedAt() != null ? Duration.between(entry.getStartedAt(), now).getSeconds() : 0))
                .toList();

        List<RetrySnapshot> retrySnapshots = retryAttempts.values().stream()
                .map(entry -> new RetrySnapshot(
                        entry.getIssueId(), entry.getAttempt(),
                        entry.getDueAt() != null ? Math.max(0, Duration.between(now, entry.getDueAt()).toMillis()) : 0,
                        entry.getIdentifier(), entry.getIssueUrl(),
                        entry.getError(), entry.getWorkerHost(), entry.getWorkspacePath()))
                .toList();

        List<BlockedSnapshot> blockedSnapshots = blocked.values().stream()
                .map(entry -> new BlockedSnapshot(
                        entry.getIssueId(), entry.getIdentifier(),
                        entry.getIssue() != null ? entry.getIssue().getUrl() : null,
                        entry.getIssue() != null ? entry.getIssue().getState() : null,
                        entry.getWorkerHost(), entry.getWorkspacePath(),
                        entry.getSessionId(), entry.getError(), entry.getBlockedAt(),
                        entry.getLastCodexTimestamp(), entry.getLastCodexEvent(),
                        entry.getLastCodexMessage()))
                .toList();

        CodexTotals codexTotals = new CodexTotals(codexInputTokens, codexOutputTokens, codexTotalTokens, secondsRunning);
        PollingStatus pollingStatus = new PollingStatus(
                pollInProgress,
                nextPollDueAt != null ? Math.max(0, Duration.between(now, nextPollDueAt).toMillis()) : null,
                pollIntervalMs);

        return new OrchestratorSnapshot(runningSnapshots, retrySnapshots, blockedSnapshots, codexTotals, pollingStatus, rateLimits);
    }

    // region Command
    public sealed interface Command permits Start, Tick, PollCycle, Snapshot, RequestRefresh, CodexUpdate, WorkerRuntimeInfo, AgentDown, RetryIssue, RefreshResult, RegisterDashboard, DispatchIssue {}
    // endregion

    // region Messages
    public enum Start implements Command { INSTANCE }
    public enum Tick implements Command { INSTANCE }
    public enum PollCycle implements Command { INSTANCE }
    public record Snapshot(ActorRef<OrchestratorSnapshot> replyTo) implements Command {}
    public enum RequestRefresh implements Command { INSTANCE }
    public record RefreshResult(boolean queued, boolean coalesced) implements Command {}
    public record CodexUpdate(String issueId, Instant timestamp, String event, java.util.Map<String, Object> payload,
                              String sessionId, Long inputTokens, Long outputTokens, Long totalTokens, RateLimits rateLimits) implements Command {}
    public record WorkerRuntimeInfo(String issueId, String workerHost, String workspacePath, String codexProcessId) implements Command {}
    public record AgentDown(String issueId, String reason) implements Command {}
    public record RetryIssue(String issueId) implements Command {}
    public record RegisterDashboard(ActorRef<StatusDashboard.Command> dashboardRef) implements Command {}
    public record DispatchIssue(Issue issue, Integer attempt, String preferredWorkerHost) implements Command {}
    // endregion
}

// region Helper Classes
class RunningEntry {
    private final String issueId;
    private final String identifier;
    private final Issue issue;
    private String agentState = "running";
    private String codexProcessId;
    private String workerHost;
    private String workspacePath;
    private String sessionId;
    private String lastCodexEvent;
    private String lastCodexMessage;
    private Instant lastCodexTimestamp;
    private final Instant startedAt;
    private long inputTokens, outputTokens, totalTokens;
    private long lastReportedInputTokens, lastReportedOutputTokens, lastReportedTotalTokens;
    private int turnCount;

    public RunningEntry(String issueId, String identifier, Issue issue, String workerHost,
                       String workspacePath, String sessionId, String lastCodexEvent,
                       String lastCodexTimestamp, Instant startedAt) {
        this.issueId = issueId;
        this.identifier = identifier;
        this.issue = issue;
        this.workerHost = workerHost;
        this.workspacePath = workspacePath;
        this.sessionId = sessionId;
        this.lastCodexEvent = lastCodexEvent;
        this.startedAt = startedAt;
    }

    public String getIssueId() { return issueId; }
    public String getIdentifier() { return identifier; }
    public Issue getIssue() { return issue; }
    public String getAgentState() { return agentState; }
    public void setAgentState(String agentState) { this.agentState = agentState; }
    public String getCodexProcessId() { return codexProcessId; }
    public void setCodexProcessId(String codexProcessId) { this.codexProcessId = codexProcessId; }
    public String getWorkerHost() { return workerHost; }
    public void setWorkerHost(String workerHost) { this.workerHost = workerHost; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getLastCodexEvent() { return lastCodexEvent; }
    public void setLastCodexEvent(String lastCodexEvent) { this.lastCodexEvent = lastCodexEvent; }
    public String getLastCodexMessage() { return lastCodexMessage; }
    public void setLastCodexMessage(String lastCodexMessage) { this.lastCodexMessage = lastCodexMessage; }
    public Instant getLastCodexTimestamp() { return lastCodexTimestamp; }
    public void setLastCodexTimestamp(Instant timestamp) { this.lastCodexTimestamp = timestamp; }
    public Instant getStartedAt() { return startedAt; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public long getLastReportedInputTokens() { return lastReportedInputTokens; }
    public long getLastReportedOutputTokens() { return lastReportedOutputTokens; }
    public long getLastReportedTotalTokens() { return lastReportedTotalTokens; }
    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
    public void setLastReportedInputTokens(long value) { this.lastReportedInputTokens = value; }
    public void setLastReportedOutputTokens(long value) { this.lastReportedOutputTokens = value; }
    public void setLastReportedTotalTokens(long value) { this.lastReportedTotalTokens = value; }
    public void addInputTokens(long delta) { this.inputTokens += delta; }
    public void addOutputTokens(long delta) { this.outputTokens += delta; }
    public void addTotalTokens(long delta) { this.totalTokens += delta; }
}

class BlockedEntry {
    private final String issueId, identifier, workerHost, workspacePath, error, sessionId;
    private final Issue issue;
    private final Instant blockedAt;
    private String lastCodexEvent;
    private String lastCodexMessage;
    private Instant lastCodexTimestamp;

    public BlockedEntry(String issueId, String identifier, Issue issue, String workerHost,
                        String workspacePath, String error, String sessionId, Instant blockedAt) {
        this.issueId = issueId;
        this.identifier = identifier;
        this.issue = issue;
        this.workerHost = workerHost;
        this.workspacePath = workspacePath;
        this.error = error;
        this.sessionId = sessionId;
        this.blockedAt = blockedAt;
    }

    public String getIssueId() { return issueId; }
    public String getIdentifier() { return identifier; }
    public Issue getIssue() { return issue; }
    public String getWorkerHost() { return workerHost; }
    public String getWorkspacePath() { return workspacePath; }
    public String getError() { return error; }
    public String getSessionId() { return sessionId; }
    public Instant getBlockedAt() { return blockedAt; }
    public String getLastCodexEvent() { return lastCodexEvent; }
    public void setLastCodexEvent(String event) { this.lastCodexEvent = event; }
    public String getLastCodexMessage() { return lastCodexMessage; }
    public void setLastCodexMessage(String message) { this.lastCodexMessage = message; }
    public Instant getLastCodexTimestamp() { return lastCodexTimestamp; }
    public void setLastCodexTimestamp(Instant ts) { this.lastCodexTimestamp = ts; }
}

class RetryEntry {
    private final String issueId, identifier, issueUrl, error, workerHost, workspacePath;
    private final int attempt;
    private final Instant dueAt;

    public RetryEntry(String issueId, int attempt, String identifier, String issueUrl,
                      String error, String workerHost, String workspacePath, Instant dueAt) {
        this.issueId = issueId;
        this.attempt = attempt;
        this.identifier = identifier;
        this.issueUrl = issueUrl;
        this.error = error;
        this.workerHost = workerHost;
        this.workspacePath = workspacePath;
        this.dueAt = dueAt;
    }

    public String getIssueId() { return issueId; }
    public int getAttempt() { return attempt; }
    public String getIdentifier() { return identifier; }
    public String getIssueUrl() { return issueUrl; }
    public String getError() { return error; }
    public String getWorkerHost() { return workerHost; }
    public String getWorkspacePath() { return workspacePath; }
    public Instant getDueAt() { return dueAt; }
}
// endregion
