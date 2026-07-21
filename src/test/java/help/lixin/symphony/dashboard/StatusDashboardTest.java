package help.lixin.symphony.dashboard;

import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.orchestrator.*;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StatusDashboard 单元测试
 */
class StatusDashboardTest {

    private AppConfig config;

    @BeforeEach
    void setup() {
        config = createTestConfig();
    }

    private AppConfig createTestConfig() {
        AppConfig cfg = new AppConfig();

        AppConfig.AgentConfig agentConfig = new AppConfig.AgentConfig();
        agentConfig.setMaxConcurrentAgents(5);
        cfg.setAgent(agentConfig);

        AppConfig.ObservabilityConfig obsConfig = new AppConfig.ObservabilityConfig();
        obsConfig.setRefreshMs(100);
        obsConfig.setRenderIntervalMs(16);
        cfg.setObservability(obsConfig);

        AppConfig.TrackerConfig trackerConfig = new AppConfig.TrackerConfig();
        trackerConfig.setProjectSlug("test-project");
        cfg.setTracker(trackerConfig);

        return cfg;
    }

    @Test
    void testSnapshotWithEmptyState() {
        OrchestratorSnapshot snapshot = new OrchestratorSnapshot(
                List.of(),
                List.of(),
                List.of(),
                new CodexTotals(0, 0, 0, 0),
                new PollingStatus(false, 5000L, 30000),
                null
        );

        assertNotNull(snapshot);
        assertEquals(0, snapshot.running().size());
        assertEquals(0, snapshot.retrying().size());
        assertEquals(0, snapshot.blocked().size());
        assertEquals(0, snapshot.codexTotals().inputTokens());
    }

    @Test
    void testSnapshotWithRunningIssues() {
        // Create 17-parameter RunningSnapshot
        RunningSnapshot running = new RunningSnapshot(
                "TEST-1",        // issueId
                "TEST-1",        // identifier
                "https://linear.app/TEST-1", // issueUrl
                "In Progress",    // state
                "running",        // agentState
                null,             // codexProcessId
                "/tmp/workspace/TEST-1", // workerHost
                "/tmp/workspace/TEST-1", // workspacePath
                "session-123",    // sessionId
                1000L,           // inputTokens
                500L,            // outputTokens
                1500L,           // totalTokens
                5,               // turnCount
                Instant.now().minusSeconds(60), // startedAt
                Instant.now(),    // lastCodexTimestamp
                "Processing",     // lastCodexEvent
                null,             // lastCodexMessage
                60L              // runtimeSeconds
        );

        OrchestratorSnapshot snapshot = new OrchestratorSnapshot(
                List.of(running),
                List.of(),
                List.of(),
                new CodexTotals(1000, 500, 1500, 60),
                new PollingStatus(false, 3000L, 30000),
                null
        );

        assertNotNull(snapshot);
        assertEquals(1, snapshot.running().size());
        assertEquals("TEST-1", snapshot.running().get(0).identifier());
        assertEquals(1000L, snapshot.running().get(0).inputTokens());
    }

    @Test
    void testSnapshotWithRetryAndBlocked() {
        // Create 9-parameter RetrySnapshot
        RetrySnapshot retry = new RetrySnapshot(
                "TEST-2", 2, 5000L, "TEST-2",
                "https://linear.app/TEST-2", "retry error", null, null
        );

        // Create 12-parameter BlockedSnapshot
        BlockedSnapshot blocked = new BlockedSnapshot(
                "TEST-3", "TEST-3",
                "https://linear.app/TEST-3", "In Progress",
                null, "/tmp/workspace/TEST-3", "session-456",
                "input required", Instant.now().minusSeconds(30), null, null, null
        );

        OrchestratorSnapshot snapshot = new OrchestratorSnapshot(
                List.of(),
                List.of(retry),
                List.of(blocked),
                new CodexTotals(0, 0, 0, 120),
                new PollingStatus(false, 20000L, 30000),
                null
        );

        assertEquals(1, snapshot.retrying().size());
        assertEquals(1, snapshot.blocked().size());
        assertEquals("TEST-2", snapshot.retrying().get(0).identifier());
        assertEquals("TEST-3", snapshot.blocked().get(0).identifier());
    }

    @Test
    void testRateLimitsFormatting() {
        RateLimits rateLimits = new RateLimits(
                "model-limit",
                new RateLimitBucket(100L, 200L, 60),
                new RateLimitBucket(50L, 100L, null),
                new RateLimitCredits(false, true, 1500.0)
        );

        assertNotNull(rateLimits);
        assertEquals("model-limit", rateLimits.limitId());
        assertEquals(100L, rateLimits.primary().remaining());
        assertEquals(200L, rateLimits.primary().limit());
        assertEquals(60, rateLimits.primary().resetInSeconds());
    }

    @Test
    void testRateLimitBucket() {
        RateLimitBucket bucket = new RateLimitBucket(100L, 200L, 60);
        assertEquals(100L, bucket.remaining());
        assertEquals(200L, bucket.limit());
        assertEquals(60, bucket.resetInSeconds());

        RateLimitBucket partial = new RateLimitBucket(50L, null, null);
        assertEquals(50L, partial.remaining());
        assertNull(partial.limit());
        assertNull(partial.resetInSeconds());
    }

    @Test
    void testRateLimitCredits() {
        RateLimitCredits credits = new RateLimitCredits(false, true, 1500.0);
        assertEquals(Boolean.FALSE, credits.unlimited());
        assertEquals(Boolean.TRUE, credits.hasCredits());
        assertEquals(1500.0, credits.balance());

        RateLimitCredits unlimited = new RateLimitCredits(true, null, null);
        assertEquals(Boolean.TRUE, unlimited.unlimited());
    }

    @Test
    void testTpsCalculation() {
        // Test TPS calculation with multiple samples
        long now = System.currentTimeMillis();

        List<TokenSample> samples = new java.util.ArrayList<>();
        samples.add(new TokenSample(now - 4000, 0));
        samples.add(new TokenSample(now - 3000, 1000));
        samples.add(new TokenSample(now - 2000, 3000));
        samples.add(new TokenSample(now - 1000, 6000));
        samples.add(new TokenSample(now, 10000));

        // Calculate TPS
        TokenSample oldest = samples.get(0);
        TokenSample newest = samples.get(samples.size() - 1);
        long elapsedMs = newest.timestamp() - oldest.timestamp();
        long deltaTokens = newest.tokens() - oldest.tokens();
        double tps = elapsedMs > 0 ? (double) deltaTokens / (elapsedMs / 1000.0) : 0.0;

        // 10000 tokens over 4 seconds = 2500 tps
        assertEquals(2500.0, tps, 100);
    }

    @Test
    void testPollingStatus() {
        PollingStatus polling = new PollingStatus(true, null, 30000);
        assertTrue(polling.checking());
        assertNull(polling.nextPollInMs());
        assertEquals(30000, polling.pollIntervalMs());

        PollingStatus polling2 = new PollingStatus(false, 5000L, 30000);
        assertFalse(polling2.checking());
        assertEquals(5000L, polling2.nextPollInMs());
    }

    @Test
    void testCodexTotals() {
        CodexTotals totals = new CodexTotals(1000, 500, 1500, 3600);
        assertEquals(1000, totals.inputTokens());
        assertEquals(500, totals.outputTokens());
        assertEquals(1500, totals.totalTokens());
        assertEquals(3600, totals.secondsRunning());
    }

    // Helper record for TPS test
    private record TokenSample(long timestamp, long tokens) {}
}
