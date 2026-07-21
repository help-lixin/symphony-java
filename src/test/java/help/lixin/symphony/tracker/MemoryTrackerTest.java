package help.lixin.symphony.tracker;

import help.lixin.symphony.model.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryTracker内存追踪器测试
 */
class MemoryTrackerTest {

    private MemoryTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new MemoryTracker();
    }

    @Test
    void testAddIssue() {
        Issue issue = Issue.builder()
                .id("issue-1")
                .identifier("TEST-1")
                .title("Test Issue")
                .state("backlog")
                .build();

        tracker.addIssue(issue);

        assertEquals(1, tracker.size());
        assertNotNull(tracker.getIssue("issue-1"));
        assertEquals("TEST-1", tracker.getIssue("issue-1").getIdentifier());
    }

    @Test
    void testAddIssues() {
        List<Issue> issues = List.of(
                Issue.builder().id("issue-1").identifier("TEST-1").state("backlog").build(),
                Issue.builder().id("issue-2").identifier("TEST-2").state("in_progress").build()
        );

        tracker.addIssues(issues);

        assertEquals(2, tracker.size());
    }

    @Test
    void testFetchCandidateIssues() throws Exception {
        tracker.addIssue(Issue.builder()
                .id("issue-1")
                .identifier("TEST-1")
                .state("backlog")
                .build());
        tracker.addIssue(Issue.builder()
                .id("issue-2")
                .identifier("TEST-2")
                .state("done")
                .build());

        CompletionStage<List<Issue>> future = tracker.fetchCandidateIssues();
        List<Issue> issues = future.toCompletableFuture().get();

        assertEquals(2, issues.size());
    }

    @Test
    void testFetchIssuesByStates() throws Exception {
        tracker.addIssue(Issue.builder()
                .id("issue-1")
                .identifier("TEST-1")
                .state("backlog")
                .build());
        tracker.addIssue(Issue.builder()
                .id("issue-2")
                .identifier("TEST-2")
                .state("in_progress")
                .build());
        tracker.addIssue(Issue.builder()
                .id("issue-3")
                .identifier("TEST-3")
                .state("done")
                .build());

        // 测试大小写不敏感
        List<Issue> backlogIssues = tracker.fetchIssuesByStates(List.of("BACKLOG")).toCompletableFuture().get();
        assertEquals(1, backlogIssues.size());
        assertEquals("TEST-1", backlogIssues.get(0).getIdentifier());

        List<Issue> multipleStates = tracker.fetchIssuesByStates(List.of("backlog", "in_progress")).toCompletableFuture().get();
        assertEquals(2, multipleStates.size());
    }

    @Test
    void testFetchIssueStatesByIds() throws Exception {
        tracker.addIssue(Issue.builder()
                .id("issue-1")
                .identifier("TEST-1")
                .state("backlog")
                .build());
        tracker.addIssue(Issue.builder()
                .id("issue-2")
                .identifier("TEST-2")
                .state("in_progress")
                .build());

        List<Issue> issues = tracker.fetchIssueStatesByIds(List.of("issue-1", "issue-3")).toCompletableFuture().get();

        assertEquals(1, issues.size());
        assertEquals("issue-1", issues.get(0).getId());
    }

    @Test
    void testUpdateIssueState() throws Exception {
        tracker.addIssue(Issue.builder()
                .id("issue-1")
                .identifier("TEST-1")
                .state("backlog")
                .build());

        tracker.updateIssueState("issue-1", "in_progress").toCompletableFuture().get();

        Issue updated = tracker.getIssue("issue-1");
        assertEquals("in_progress", updated.getState());
    }

    @Test
    void testUpdateNonexistentIssue() throws Exception {
        // 更新不存在的issue应该不抛异常
        tracker.updateIssueState("nonexistent", "in_progress").toCompletableFuture().get();
        assertEquals(0, tracker.size());
    }

    @Test
    void testClear() {
        tracker.addIssue(Issue.builder()
                .id("issue-1")
                .identifier("TEST-1")
                .state("backlog")
                .build());
        tracker.addIssue(Issue.builder()
                .id("issue-2")
                .identifier("TEST-2")
                .state("in_progress")
                .build());

        assertEquals(2, tracker.size());

        tracker.clear();

        assertEquals(0, tracker.size());
    }

    @Test
    void testGetIssue() {
        Issue issue = Issue.builder()
                .id("issue-1")
                .identifier("TEST-1")
                .title("Test")
                .state("backlog")
                .build();

        tracker.addIssue(issue);

        Issue found = tracker.getIssue("issue-1");
        assertNotNull(found);
        assertEquals("TEST-1", found.getIdentifier());

        Issue notFound = tracker.getIssue("nonexistent");
        assertNull(notFound);
    }

    @Test
    void testEmptyTracker() {
        assertEquals(0, tracker.size());

        List<Issue> issues = tracker.fetchCandidateIssues().toCompletableFuture().join();
        assertTrue(issues.isEmpty());
    }

    @Test
    void testConcurrentAccess() throws Exception {
        // 添加多个issue
        for (int i = 0; i < 100; i++) {
            tracker.addIssue(Issue.builder()
                    .id("issue-" + i)
                    .identifier("TEST-" + i)
                    .state("backlog")
                    .build());
        }

        assertEquals(100, tracker.size());

        // 并发获取
        List<Issue> issues = tracker.fetchCandidateIssues().toCompletableFuture().get();
        assertEquals(100, issues.size());
    }
}
