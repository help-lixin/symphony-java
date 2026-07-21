package help.lixin.symphony.tracker;

import help.lixin.symphony.model.Issue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存问题追踪器（用于测试）
 *
 * 功能说明:
 * - 实现Tracker接口
 * - 将问题存储在内存中
 * - 方便单元测试和集成测试
 *
 * 特性:
 * - 线程安全
 * - 支持动态添加/修改问题
 * - 可重置状态
 */
public class MemoryTracker implements Tracker {
    private static final Logger logger = LoggerFactory.getLogger(MemoryTracker.class);

    private final Map<String, Issue> issues = new ConcurrentHashMap<>();
    private final Map<String, String> issueStates = new ConcurrentHashMap<>();

    public MemoryTracker() {
        logger.info("创建内存追踪器");
    }

    /**
     * 添加或更新问题
     *
     * @param issue 问题对象
     */
    public void addIssue(Issue issue) {
        issues.put(issue.getId(), issue);
        issueStates.put(issue.getId(), issue.getState());
        logger.debug("添加问题: {}", issue.getIdentifier());
    }

    /**
     * 批量添加问题
     *
     * @param issueList 问题列表
     */
    public void addIssues(List<Issue> issueList) {
        for (Issue issue : issueList) {
            addIssue(issue);
        }
    }

    /**
     * 更新问题状态
     *
     * @param issueId 问题ID
     * @param newState 新状态
     * @return 异步完成阶段
     */
    @Override
    public CompletionStage<Void> updateIssueState(String issueId, String newState) {
        Issue issue = issues.get(issueId);
        if (issue != null) {
            Issue updated = Issue.builder()
                    .id(issue.getId())
                    .identifier(issue.getIdentifier())
                    .title(issue.getTitle())
                    .description(issue.getDescription())
                    .priority(issue.getPriority())
                    .state(newState)
                    .branchName(issue.getBranchName())
                    .url(issue.getUrl())
                    .assigneeId(issue.getAssigneeId())
                    .labels(issue.getLabels())
                    .blockedBy(issue.getBlockedBy())
                    .assignedToWorker(issue.isAssignedToWorker())
                    .createdAt(issue.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();

            issues.put(issueId, updated);
            issueStates.put(issueId, newState);
            logger.debug("更新问题状态: {} -> {}", issue.getIdentifier(), newState);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 获取候选问题列表
     *
     * @return 候选问题列表
     */
    @Override
    public CompletionStage<List<Issue>> fetchCandidateIssues() {
        return CompletableFuture.completedFuture(new ArrayList<>(issues.values()));
    }

    /**
     * 根据状态列表获取问题
     *
     * @param states 状态名称列表
     * @return 问题列表
     */
    @Override
    public CompletionStage<List<Issue>> fetchIssuesByStates(List<String> states) {
        List<String> normalizedStates = states.stream()
                .map(String::toLowerCase)
                .map(String::trim)
                .toList();

        List<Issue> filtered = issues.values().stream()
                .filter(issue -> {
                    if (issue.getState() == null) return false;
                    String normalizedState = issue.getState().toLowerCase().trim();
                    return normalizedStates.contains(normalizedState);
                })
                .toList();

        return CompletableFuture.completedFuture(filtered);
    }

    /**
     * 根据ID列表获取问题状态
     *
     * @param issueIds 问题ID列表
     * @return 问题列表
     */
    @Override
    public CompletionStage<List<Issue>> fetchIssueStatesByIds(List<String> issueIds) {
        List<Issue> result = new ArrayList<>();
        for (String id : issueIds) {
            Issue issue = issues.get(id);
            if (issue != null) {
                result.add(issue);
            }
        }
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 清空所有问题
     */
    public void clear() {
        issues.clear();
        issueStates.clear();
        logger.debug("清空内存追踪器");
    }

    /**
     * 获取问题数量
     *
     * @return 问题数量
     */
    public int size() {
        return issues.size();
    }

    /**
     * 获取指定问题
     *
     * @param issueId 问题ID
     * @return 问题对象
     */
    public Issue getIssue(String issueId) {
        return issues.get(issueId);
    }
}
