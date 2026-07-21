package help.lixin.symphony.tracker;

import help.lixin.symphony.model.Issue;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 问题追踪器接口
 *
 * 功能说明:
 * - 定义问题追踪器的抽象接口
 * - 支持获取候选问题、状态查询等操作
 * - 实现异步非阻塞调用
 *
 * 实现类:
 * - LinearTracker: Linear API实现
 * - MemoryTracker: 内存实现（用于测试）
 */
public interface Tracker {

    /**
     * 获取候选问题列表
     *
     * 功能说明:
     * - 查询处于活跃状态的问题
     * - 根据工作流配置进行过滤
     * - 支持分配给当前worker的过滤
     *
     * @return 候选问题列表的CompletionStage
     */
    CompletionStage<List<Issue>> fetchCandidateIssues();

    /**
     * 根据状态列表获取问题
     *
     * @param states 状态名称列表
     * @return 问题列表的CompletionStage
     */
    CompletionStage<List<Issue>> fetchIssuesByStates(List<String> states);

    /**
     * 根据ID列表获取问题的最新状态
     *
     * @param issueIds 问题ID列表
     * @return 问题列表的CompletionStage
     */
    CompletionStage<List<Issue>> fetchIssueStatesByIds(List<String> issueIds);

    /**
     * 创建评论
     *
     * @param issueId 问题ID
     * @param body 评论内容
     * @return 操作结果的CompletionStage
     */
    default CompletionStage<Void> createComment(String issueId, String body) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("createComment not implemented"));
    }

    /**
     * 更新问题状态
     *
     * @param issueId 问题ID
     * @param stateName 新状态名称
     * @return 操作结果的CompletionStage
     */
    default CompletionStage<Void> updateIssueState(String issueId, String stateName) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("updateIssueState not implemented"));
    }
}
