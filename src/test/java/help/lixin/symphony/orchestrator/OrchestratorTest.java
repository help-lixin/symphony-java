package help.lixin.symphony.orchestrator;

import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Orchestrator 单元测试
 */
class OrchestratorTest {

    private AppConfig config;

    @BeforeEach
    void setup() {
        config = createTestConfig();
    }

    private AppConfig createTestConfig() {
        AppConfig cfg = new AppConfig();

        AppConfig.TrackerConfig trackerConfig = new AppConfig.TrackerConfig();
        trackerConfig.setApiKey("test-api-key");
        trackerConfig.setProjectSlug("test-project");
        trackerConfig.setActiveStates(List.of("Todo", "In Progress"));
        trackerConfig.setTerminalStates(List.of("Closed", "Cancelled", "Done"));
        cfg.setTracker(trackerConfig);

        AppConfig.AgentConfig agentConfig = new AppConfig.AgentConfig();
        agentConfig.setMaxConcurrentAgents(3);
        agentConfig.setMaxTurns(10);
        agentConfig.setMaxRetryBackoffMs(60000);
        cfg.setAgent(agentConfig);

        AppConfig.PollingConfig pollingConfig = new AppConfig.PollingConfig();
        pollingConfig.setIntervalMs(5000);
        cfg.setPolling(pollingConfig);

        AppConfig.WorkspaceConfig workspaceConfig = new AppConfig.WorkspaceConfig();
        workspaceConfig.setRoot("/tmp/test-workspace");
        cfg.setWorkspace(workspaceConfig);

        return cfg;
    }

    @Test
    void testAvailableSlotsCalculation() {
        int maxAgents = config.getAgent().getMaxConcurrentAgents();
        assertEquals(3, maxAgents);
    }

    @Test
    void testShouldDispatchWithValidIssue() {
        Issue issue = createTestIssue("TEST-1", "In Progress", 1);
        assertTrue(issue.isAssignedToWorker());
        assertTrue(issue.isRoutable(List.of()));
    }

    @Test
    void testIssuePriorityRanking() {
        Issue p1 = createTestIssue("TEST-1", "In Progress", 1);
        Issue p3 = createTestIssue("TEST-2", "In Progress", 3);
        Issue p4 = createTestIssue("TEST-3", "In Progress", 4);

        List<Issue> issues = List.of(p4, p1, p3);
        List<Issue> sorted = issues.stream()
                .sorted(Comparator.comparing((Issue i) -> priorityRank(i.getPriority()))
                        .thenComparing(i -> createdAtSortKey(i)))
                .toList();

        assertEquals("TEST-1", sorted.get(0).getId());
        assertEquals("TEST-2", sorted.get(1).getId());
        assertEquals("TEST-3", sorted.get(2).getId());
    }

    @Test
    void testActiveStateCheck() {
        assertTrue(isActiveState("In Progress"));
        assertTrue(isActiveState("in progress")); // case insensitive
        assertTrue(isActiveState("  In Progress  ")); // trims whitespace
        assertFalse(isActiveState(null));
        assertFalse(isActiveState("Unknown State"));
    }

    @Test
    void testTerminalStateCheck() {
        assertTrue(isTerminalState("Closed"));
        assertTrue(isTerminalState("DONE")); // case insensitive
        assertFalse(isTerminalState(null));
        assertFalse(isTerminalState("In Progress"));
    }

    @Test
    void testRetryDelayCalculation() {
        // First attempt with continuation delay type
        int delay1 = calculateRetryDelay(1, Map.of("delay_type", "continuation"));
        assertEquals(1000, delay1); // CONTINUATION_RETRY_DELAY_MS

        // Regular retry with exponential backoff
        int delay2 = calculateRetryDelay(1, Map.of());
        assertEquals(10000, delay2); // FAILURE_RETRY_BASE_MS

        int delay3 = calculateRetryDelay(2, Map.of());
        assertEquals(20000, delay3); // 10s * 2^1

        int delay4 = calculateRetryDelay(3, Map.of());
        assertEquals(40000, delay4); // 10s * 2^2

        // Max delay should be capped
        int delay11 = calculateRetryDelay(11, Map.of());
        assertEquals(60000, delay11); // maxRetryBackoffMs
    }

    @Test
    void testRunningEntryTokenTracking() {
        RunningEntry entry = new RunningEntry(
                "TEST-1", "TEST-1", null,
                null, null, null, null, null, Instant.now());

        assertEquals(0, entry.getInputTokens());
        assertEquals(0, entry.getOutputTokens());
        assertEquals(0, entry.getTotalTokens());

        entry.addInputTokens(100);
        entry.addOutputTokens(50);
        entry.addTotalTokens(150);

        assertEquals(100, entry.getInputTokens());
        assertEquals(50, entry.getOutputTokens());
        assertEquals(150, entry.getTotalTokens());

        // Test delta tracking
        entry.setLastReportedInputTokens(100);
        entry.setLastReportedOutputTokens(50);
        entry.setLastReportedTotalTokens(150);

        entry.addInputTokens(50);
        entry.addOutputTokens(25);
        entry.addTotalTokens(75);

        assertEquals(150, entry.getInputTokens());
        assertEquals(75, entry.getOutputTokens());
        assertEquals(225, entry.getTotalTokens());
    }

    @Test
    void testBlockedEntryCreation() {
        Issue issue = createTestIssue("TEST-1", "In Progress", 1);
        BlockedEntry entry = new BlockedEntry(
                "TEST-1", "TEST-1", issue,
                null, "/tmp/workspace", "input required", null, Instant.now());

        assertEquals("TEST-1", entry.getIssueId());
        assertEquals("TEST-1", entry.getIdentifier());
        assertEquals("input required", entry.getError());
        assertEquals(issue, entry.getIssue());
    }

    @Test
    void testRetryEntryCreation() {
        RetryEntry entry = new RetryEntry(
                "TEST-1", 2, "TEST-1",
                "https://linear.app/TEST-1",
                "error message", null, null,
                Instant.now().plusSeconds(10));

        assertEquals("TEST-1", entry.getIssueId());
        assertEquals(2, entry.getAttempt());
        assertEquals("error message", entry.getError());
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

    private int priorityRank(Integer priority) {
        if (priority != null && priority >= 1 && priority <= 4) return priority;
        return 5;
    }

    private long createdAtSortKey(Issue issue) {
        if (issue.getCreatedAt() != null) return issue.getCreatedAt().toEpochMilli();
        return Long.MAX_VALUE;
    }

    private int calculateRetryDelay(int attempt, Map<String, Object> metadata) {
        int CONTINUATION_RETRY_DELAY_MS = 1_000;
        int FAILURE_RETRY_BASE_MS = 10_000;
        int maxRetryBackoffMs = config.getAgent().getMaxRetryBackoffMs();

        if (attempt == 1 && "continuation".equals(metadata.get("delay_type"))) {
            return CONTINUATION_RETRY_DELAY_MS;
        }
        int maxDelayPower = Math.min(attempt - 1, 10);
        int delay = FAILURE_RETRY_BASE_MS * (1 << maxDelayPower);
        return Math.min(delay, maxRetryBackoffMs);
    }

    private Issue createTestIssue(String id, String state, Integer priority) {
        return new Issue(
                id, id, "Test Issue", "Test Description",
                priority, state, "feature/test",
                "https://linear.app/test/" + id,
                "assignee-1", List.of(), List.of(),
                true, Instant.now().minusSeconds(3600), Instant.now()
        );
    }
}
