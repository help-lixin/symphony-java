package help.lixin.symphony.tracker;

import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Linear问题追踪器实现
 *
 * 功能说明:
 * - 实现Tracker接口
 * - 通过GraphQL API与Linear通信
 * - 处理分页、过滤和规范化
 *
 * GraphQL查询:
 * - 使用批量查询减少API调用
 * - 支持分页获取大量问题
 * - 自动规范化问题数据
 */
public class LinearTracker implements Tracker {
    private static final Logger logger = LoggerFactory.getLogger(LinearTracker.class);

    private final LinearClient client;
    private final AppConfig.TrackerConfig config;

    public LinearTracker(AppConfig config) {
        this.config = config.getTracker();
        this.client = new LinearClient(this.config);
    }

    /**
     * 获取候选问题列表
     *
     * 功能说明:
     * - 查询处于活跃状态的问题
     * - 支持按分配对象过滤
     * - 支持必需标签过滤
     *
     * @return 候选问题列表
     */
    @Override
    public CompletionStage<List<Issue>> fetchCandidateIssues() {
        logger.debug("获取候选问题: projectSlug={}, activeStates={}",
                config.getProjectSlug(), config.getActiveStates());

        return client.fetchIssues(config.getProjectSlug(), config.getActiveStates())
                .thenApply(issues -> {
                    logger.debug("获取到{}个候选问题", issues.size());
                    return filterAndNormalizeIssues(issues);
                });
    }

    /**
     * 根据状态列表获取问题
     *
     * @param states 状态名称列表
     * @return 问题列表
     */
    @Override
    public CompletionStage<List<Issue>> fetchIssuesByStates(List<String> states) {
        logger.debug("获取问题列表: states={}", states);

        return client.fetchIssues(config.getProjectSlug(), states)
                .thenApply(issues -> {
                    logger.debug("获取到{}个问题", issues.size());
                    return issues;
                });
    }

    /**
     * 根据ID列表获取问题状态
     *
     * @param issueIds 问题ID列表
     * @return 问题列表
     */
    @Override
    public CompletionStage<List<Issue>> fetchIssueStatesByIds(List<String> issueIds) {
        if (issueIds == null || issueIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        logger.debug("批量获取问题状态: count={}", issueIds.size());

        return client.fetchIssuesByIds(issueIds)
                .thenApply(issues -> {
                    logger.debug("获取到{}个问题状态", issues.size());
                    return issues;
                });
    }

    /**
     * 过滤并规范化问题
     *
     * @param issues 原始问题列表
     * @return 过滤后的问题列表
     */
    private List<Issue> filterAndNormalizeIssues(List<Issue> issues) {
        List<String> requiredLabels = config.getRequiredLabels();

        return issues.stream()
                .filter(issue -> issue.isRoutable(requiredLabels))
                .toList();
    }

    /**
     * 创建评论
     *
     * @param issueId 问题ID
     * @param body 评论内容
     * @return 操作结果
     */
    @Override
    public CompletionStage<Void> createComment(String issueId, String body) {
        return client.createComment(issueId, body)
                .thenAccept(success -> {
                    if (!success) {
                        throw new RuntimeException("Failed to create comment for issue: " + issueId);
                    }
                })
                .thenApply(v -> null);
    }

    /**
     * 更新问题状态
     *
     * @param issueId 问题ID
     * @param stateName 新状态名称
     * @return 操作结果
     */
    @Override
    public CompletionStage<Void> updateIssueState(String issueId, String stateName) {
        return client.updateIssueState(issueId, stateName)
                .thenAccept(success -> {
                    if (!success) {
                        throw new RuntimeException("Failed to update issue state: " + issueId + " to " + stateName);
                    }
                })
                .thenApply(v -> null);
    }
}
