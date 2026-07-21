package help.lixin.symphony.dashboard;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.orchestrator.Orchestrator;
import help.lixin.symphony.orchestrator.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 状态仪表板Actor (Akka Typed)
 */
public class StatusDashboard extends AbstractBehavior<StatusDashboard.Command> {

    private static final Logger logger = LoggerFactory.getLogger(StatusDashboard.class);

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String BLUE = "\033[34m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String GRAY = "\033[90m";

    private static final int COL_ID = 10;
    private static final int COL_STATE = 12;
    private static final int COL_PID = 10;
    private static final int COL_AGE = 12;
    private static final int COL_TOKENS = 10;
    private static final int COL_SESSION = 14;
    private static final int COL_EVENT_MIN = 30;

    private final AppConfig config;
    private final ActorRef<Orchestrator.Command> orchestratorRef;
    private final int refreshMs;
    private final int renderIntervalMs;

    private final List<TokenSample> tokenSamples = new ArrayList<>();
    private Long lastTpsSecond = null;
    private Double lastTpsValue = null;
    private String lastRenderedContent;
    private Instant lastRenderedAt;
    private OrchestratorSnapshot latestSnapshot;

    private final String TICK_TIMER_KEY = "dashboard-tick";
    private final TimerScheduler<Command> timers;
    private ActorRef<OrchestratorSnapshot> snapshotDeadLetter;

    public StatusDashboard(ActorContext<Command> context,
                         TimerScheduler<Command> timers,
                         AppConfig config,
                         ActorRef<Orchestrator.Command> orchestratorRef) {
        super(context);
        this.timers = timers;
        this.config = config;
        this.orchestratorRef = orchestratorRef;
        this.refreshMs = config.getObservability().getRefreshMs();
        this.renderIntervalMs = config.getObservability().getRenderIntervalMs();
    }

    public static Behavior<Command> create(AppConfig config, ActorRef<Orchestrator.Command> orchestratorRef) {
        return Behaviors.withTimers(timers -> Behaviors.setup(ctx ->
                new StatusDashboard(ctx, timers, config, orchestratorRef).start()));
    }

    private StatusDashboard start() {
        logger.info("StatusDashboard启动");
        // Create a "dead letter" actor to receive snapshot replies we don't need
        this.snapshotDeadLetter = getContext().spawn(
            Behaviors.ignore(),
            "snapshot-deadletter"
        );
        scheduleTick();
        return this;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Tick.class, msg -> handleTick())
                .onMessage(SnapshotResult.class, msg -> handleSnapshotResult(msg))
                .build();
    }

    private Behavior<Command> handleTick() {
        // Request snapshot from orchestrator
        orchestratorRef.tell(new Orchestrator.Snapshot(snapshotDeadLetter));
        scheduleTick();
        return Behaviors.same();
    }

    private Behavior<Command> handleSnapshotResult(SnapshotResult result) {
        OrchestratorSnapshot snapshot = result.snapshot();
        latestSnapshot = snapshot;
        updateTokenSamples(snapshot.codexTotals().totalTokens());
        double tps = calculateTps();
        Instant now = Instant.now();

        if (shouldRender(now)) {
            render(snapshot, tps);
            lastRenderedAt = now;
        }
        return Behaviors.same();
    }

    private void updateTokenSamples(long currentTotalTokens) {
        long nowMs = System.currentTimeMillis();
        long windowMs = 5000;
        tokenSamples.add(new TokenSample(nowMs, currentTotalTokens));
        long cutoff = nowMs - windowMs;
        tokenSamples.removeIf(s -> s.timestamp() < cutoff);
    }

    private double calculateTps() {
        long nowMs = System.currentTimeMillis();
        long currentSecond = nowMs / 1000;

        if (lastTpsSecond != null && lastTpsSecond == currentSecond && lastTpsValue != null) {
            return lastTpsValue;
        }

        if (tokenSamples.size() < 2) {
            lastTpsSecond = currentSecond;
            lastTpsValue = 0.0;
            return 0.0;
        }

        TokenSample oldest = tokenSamples.get(0);
        TokenSample newest = tokenSamples.get(tokenSamples.size() - 1);
        long elapsedMs = newest.timestamp() - oldest.timestamp();
        long deltaTokens = newest.tokens() - oldest.tokens();
        double tps = elapsedMs > 0 ? (double) deltaTokens / (elapsedMs / 1000.0) : 0.0;

        lastTpsSecond = currentSecond;
        lastTpsValue = tps;
        return tps;
    }

    private boolean shouldRender(Instant now) {
        if (lastRenderedAt == null) return true;
        long elapsedMs = Duration.between(lastRenderedAt, now).toMillis();
        return elapsedMs >= renderIntervalMs;
    }

    private void scheduleTick() {
        timers.startSingleTimer(
                TICK_TIMER_KEY, Tick.INSTANCE, Duration.ofMillis(refreshMs));
    }

    private void render(OrchestratorSnapshot snapshot, double tps) {
        StringBuilder sb = new StringBuilder();

        sb.append(CLEAR_SCREEN).append(CURSOR_HOME);
        sb.append(BOLD).append("╭─ SYMPHONY STATUS").append(RESET).append("\n");

        int agentCount = snapshot.running().size();
        int maxAgents = config.getAgent().getMaxConcurrentAgents();
        sb.append("│ ").append(BOLD).append("Agents: ").append(RESET)
                .append(GREEN).append(String.valueOf(agentCount)).append(RESET)
                .append("/").append(GRAY).append(String.valueOf(maxAgents)).append(RESET).append("\n");

        sb.append("│ ").append(BOLD).append("Throughput: ").append(RESET)
                .append(CYAN).append(formatTps(tps)).append(" tps").append(RESET).append("\n");

        sb.append("│ ").append(BOLD).append("Runtime: ").append(RESET)
                .append(MAGENTA).append(formatRuntimeSeconds(snapshot.codexTotals().secondsRunning())).append(RESET).append("\n");

        long inputTokens = snapshot.codexTotals().inputTokens();
        long outputTokens = snapshot.codexTotals().outputTokens();
        long totalTokens = snapshot.codexTotals().totalTokens();
        sb.append("│ ").append(BOLD).append("Tokens: ").append(RESET)
                .append("in ").append(YELLOW).append(formatCount(inputTokens)).append(RESET).append(" | ")
                .append("out ").append(YELLOW).append(formatCount(outputTokens)).append(RESET).append(" | ")
                .append("total ").append(YELLOW).append(formatCount(totalTokens)).append(RESET).append("\n");

        sb.append("│ ").append(BOLD).append("Project: ").append(RESET)
                .append(CYAN).append(formatProjectLink()).append(RESET).append("\n");

        sb.append("│ ").append(BOLD).append("Rate Limits: ").append(RESET)
                .append(formatRateLimits(snapshot.rateLimits())).append("\n");

        if (snapshot.polling().checking()) {
            sb.append("│ ").append(BOLD).append("Next refresh: ").append(RESET)
                    .append(CYAN).append("checking now...").append(RESET).append("\n");
        } else if (snapshot.polling().nextPollInMs() != null) {
            long seconds = (snapshot.polling().nextPollInMs() + 999) / 1000;
            sb.append("│ ").append(BOLD).append("Next refresh: ").append(RESET)
                    .append(CYAN).append(seconds + "s").append(RESET).append("\n");
        }

        sb.append(BOLD).append("├─ Running").append(RESET).append("\n").append("│").append("\n");

        if (snapshot.running().isEmpty()) {
            sb.append("│  ").append(GRAY).append("No active agents").append(RESET).append("\n");
        } else {
            sb.append("│   ")
                    .append(GRAY).append(formatCell("ID", COL_ID)).append(RESET).append(" ")
                    .append(GRAY).append(formatCell("STAGE", COL_STATE)).append(RESET).append(" ")
                    .append(GRAY).append(formatCell("PID", COL_PID)).append(RESET).append(" ")
                    .append(GRAY).append(formatCell("AGE/TURN", COL_AGE)).append(RESET).append(" ")
                    .append(GRAY).append(formatCell("TOKENS", COL_TOKENS)).append(RESET).append(" ")
                    .append(GRAY).append(formatCell("SESSION", COL_SESSION)).append(RESET).append(" ")
                    .append(GRAY).append(formatCell("EVENT", COL_EVENT_MIN)).append(RESET).append("\n");

            int totalWidth = COL_ID + COL_STATE + COL_PID + COL_AGE + COL_TOKENS + COL_SESSION + COL_EVENT_MIN + 6;
            sb.append("│   ").append(GRAY).append("─".repeat(totalWidth)).append(RESET).append("\n");

            for (RunningSnapshot entry : snapshot.running()) {
                String pidDisplay = entry.codexProcessId() != null ? entry.codexProcessId() : "n/a";
                String ageTurnDisplay = formatAgeTurn(entry.runtimeSeconds(), entry.turnCount());
                String tokensDisplay = formatCount(entry.totalTokens());
                String sessionDisplay = formatSession(entry.sessionId());

                sb.append("│   ")
                        .append(CYAN).append(formatCell(entry.identifier(), COL_ID)).append(RESET).append(" ")
                        .append(BLUE).append(formatCell(entry.state() != null ? entry.state() : "unknown", COL_STATE)).append(RESET).append(" ")
                        .append(YELLOW).append(formatCell(pidDisplay, COL_PID)).append(RESET).append(" ")
                        .append(MAGENTA).append(formatCell(ageTurnDisplay, COL_AGE)).append(RESET).append(" ")
                        .append(YELLOW).append(formatCell(tokensDisplay, COL_TOKENS)).append(RESET).append(" ")
                        .append(CYAN).append(formatCell(sessionDisplay, COL_SESSION)).append(RESET).append(" ")
                        .append(BLUE).append(formatCell(entry.lastCodexEvent() != null ? entry.lastCodexEvent() : "none", COL_EVENT_MIN)).append(RESET).append("\n");
            }
        }

        sb.append(BOLD).append("├─ Backoff queue").append(RESET).append("\n").append("│").append("\n");

        if (snapshot.retrying().isEmpty()) {
            sb.append("│  ").append(GRAY).append("No queued retries").append(RESET).append("\n");
        } else {
            for (RetrySnapshot retry : snapshot.retrying()) {
                sb.append("│  ")
                        .append(RED).append("↻ ").append(retry.identifier()).append(RESET)
                        .append(" attempt=").append(YELLOW).append(retry.attempt()).append(RESET)
                        .append(" in ").append(CYAN).append(formatDelay(retry.dueInMs())).append(RESET).append("\n");
            }
        }

        sb.append(BOLD).append("├─ Blocked").append(RESET).append("\n").append("│").append("\n");

        if (snapshot.blocked().isEmpty()) {
            sb.append("│  ").append(GRAY).append("No blocked issues").append(RESET).append("\n");
        } else {
            for (BlockedSnapshot blocked : snapshot.blocked()) {
                sb.append("│  ")
                        .append(RED).append("⚠ ").append(blocked.identifier()).append(RESET)
                        .append(" ")
                        .append(GRAY).append(blocked.error() != null ? truncate(blocked.error(), 50) : "").append(RESET).append("\n");
            }
        }

        sb.append("╰─\n");
        System.out.print(sb.toString());
        lastRenderedContent = sb.toString();
    }

    private String formatCell(String value, int width) {
        if (value == null) value = "";
        if (value.length() > width) return value.substring(0, width - 3) + "...";
        return String.format("%-" + width + "s", value);
    }

    private String formatRuntimeSeconds(long seconds) {
        long mins = seconds / 60;
        long secs = seconds % 60;
        return String.format("%dm %ds", mins, secs);
    }

    private String formatCount(long count) {
        return String.format("%,d", count);
    }

    private String formatTps(double tps) {
        return String.format("%,.0f", tps);
    }

    private String formatDelay(long ms) {
        long secs = ms / 1000;
        long millis = ms % 1000;
        return String.format("%d.%03ds", secs, millis);
    }

    private String formatProjectLink() {
        String slug = config.getTracker().getProjectSlug();
        if (slug == null || slug.isBlank()) return "n/a";
        return slug;
    }

    private String formatSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "n/a";
        if (sessionId.length() > 10) return sessionId.substring(0, 4) + "..." + sessionId.substring(sessionId.length() - 6);
        return sessionId;
    }

    private String formatAgeTurn(long runtimeSeconds, int turnCount) {
        return formatRuntimeSeconds(runtimeSeconds) + "/" + turnCount;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        if (value.length() <= maxLen) return value;
        return value.substring(0, maxLen - 3) + "...";
    }

    private String formatRateLimits(RateLimits rateLimits) {
        if (rateLimits == null) return GRAY + "unavailable" + RESET;
        String limitId = rateLimits.limitId() != null ? rateLimits.limitId() : "unknown";
        String primary = formatRateLimitBucket(rateLimits.primary());
        String secondary = formatRateLimitBucket(rateLimits.secondary());
        String credits = formatRateLimitCredits(rateLimits.credits());
        return YELLOW + limitId + RESET + " | " + CYAN + "primary " + primary + RESET +
                " | " + CYAN + "secondary " + secondary + RESET + " | " + GREEN + credits + RESET;
    }

    private String formatRateLimitBucket(RateLimitBucket bucket) {
        if (bucket == null) return "n/a";
        StringBuilder sb = new StringBuilder();
        if (bucket.remaining() != null && bucket.limit() != null) {
            sb.append(formatCount(bucket.remaining())).append("/").append(formatCount(bucket.limit()));
        } else if (bucket.remaining() != null) {
            sb.append("remaining ").append(formatCount(bucket.remaining()));
        } else if (bucket.limit() != null) {
            sb.append("limit ").append(formatCount(bucket.limit()));
        } else {
            return "n/a";
        }
        if (bucket.resetInSeconds() != null) sb.append(" reset ").append(bucket.resetInSeconds()).append("s");
        return sb.toString();
    }

    private String formatRateLimitCredits(RateLimitCredits credits) {
        if (credits == null) return "credits n/a";
        if (Boolean.TRUE.equals(credits.unlimited())) return "credits unlimited";
        if (Boolean.TRUE.equals(credits.hasCredits())) {
            if (credits.balance() != null) return "credits " + formatCount(credits.balance().longValue());
            return "credits available";
        }
        return "credits none";
    }

    private static final String CLEAR_SCREEN = "\033[2J";
    private static final String CURSOR_HOME = "\033[H";

    private record TokenSample(long timestamp, long tokens) {}

    // region Command
    public sealed interface Command permits Tick, SnapshotResult {}
    // endregion

    // region Messages
    public enum Tick implements Command { INSTANCE }
    public record SnapshotResult(OrchestratorSnapshot snapshot) implements Command {}
    // endregion
}
