package help.lixin.symphony.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue数据模型测试
 */
class IssueTest {

    @Test
    void testBuilder() {
        Issue issue = Issue.builder()
                .id("issue-123")
                .identifier("PROJ-1")
                .title("Test Issue")
                .description("Test description")
                .priority(1)
                .state("in_progress")
                .branchName("feature/PROJ-1")
                .url("https://linear.app/proj/issue/PROJ-1")
                .assigneeId("user-456")
                .labels(List.of("bug", "urgent"))
                .blockedBy(List.of())
                .assignedToWorker(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        assertEquals("issue-123", issue.getId());
        assertEquals("PROJ-1", issue.getIdentifier());
        assertEquals("Test Issue", issue.getTitle());
        assertEquals("Test description", issue.getDescription());
        assertEquals(1, issue.getPriority());
        assertEquals("in_progress", issue.getState());
        assertEquals("feature/PROJ-1", issue.getBranchName());
        assertEquals("https://linear.app/proj/issue/PROJ-1", issue.getUrl());
        assertEquals("user-456", issue.getAssigneeId());
        assertEquals(2, issue.getLabels().size());
        assertFalse(issue.isAssignedToWorker());
    }

    @Test
    void testWithBlocker() {
        Issue.Blocker blocker = new Issue.Blocker("blocker-1", "PROJ-0", "done");
        Issue issue = Issue.builder()
                .id("issue-123")
                .identifier("PROJ-1")
                .title("Test Issue")
                .blockedBy(List.of(blocker))
                .build();

        assertEquals(1, issue.getBlockedBy().size());
        assertEquals("blocker-1", issue.getBlockedBy().get(0).getId());
    }

    @Test
    void testIsRoutable() {
        // 根据实际isRoutable逻辑:
        // assignedToWorker=false时返回false
        Issue issueUnassigned = Issue.builder()
                .id("issue-123")
                .identifier("PROJ-1")
                .assignedToWorker(false)
                .build();

        assertFalse(issueUnassigned.isRoutable(null));

        // assignedToWorker=true且无必需标签时返回true
        Issue issueAssigned = Issue.builder()
                .id("issue-456")
                .identifier("PROJ-2")
                .assignedToWorker(true)
                .build();

        assertTrue(issueAssigned.isRoutable(null));
        assertTrue(issueAssigned.isRoutable(List.of()));
    }

    @Test
    void testGetLabels() {
        Issue issue = Issue.builder()
                .id("issue-123")
                .identifier("PROJ-1")
                .labels(List.of("bug", "feature"))
                .build();

        List<String> labels = issue.getLabels();
        assertEquals(2, labels.size());
        assertTrue(labels.contains("bug"));
        assertTrue(labels.contains("feature"));
    }

    @Test
    void testPrioritySorting() {
        // 测试优先级比较
        Issue lowPriority = Issue.builder().id("1").identifier("LOW").priority(5).build();
        Issue highPriority = Issue.builder().id("2").identifier("HIGH").priority(1).build();
        Issue mediumPriority = Issue.builder().id("3").identifier("MED").priority(3).build();

        // 优先级数字越小越紧急
        assertTrue(highPriority.getPriority() < lowPriority.getPriority());
        assertTrue(mediumPriority.getPriority() < lowPriority.getPriority());
    }

    @Test
    void testToString() {
        Issue issue = Issue.builder()
                .id("issue-123")
                .identifier("PROJ-1")
                .title("Test")
                .build();

        String str = issue.toString();
        assertNotNull(str);
        assertTrue(str.contains("PROJ-1"));
        assertTrue(str.contains("Test"));
    }

    @Test
    void testBlockerClass() {
        Issue.Blocker blocker = new Issue.Blocker("blocker-1", "PROJ-0", "done");

        assertEquals("blocker-1", blocker.getId());
        assertEquals("PROJ-0", blocker.getIdentifier());
        assertEquals("done", blocker.getState());
    }

    @Test
    void testBlockerEquality() {
        Issue.Blocker blocker1 = new Issue.Blocker("id-1", "PROJ-1", "done");
        Issue.Blocker blocker2 = new Issue.Blocker("id-1", "PROJ-1", "done");
        Issue.Blocker blocker3 = new Issue.Blocker("id-2", "PROJ-2", "done");

        assertEquals(blocker1, blocker2);
        assertNotEquals(blocker1, blocker3);
    }
}
