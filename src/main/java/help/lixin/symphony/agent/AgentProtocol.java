package help.lixin.symphony.agent;

import help.lixin.symphony.model.Issue;

import java.time.Instant;

/**
 * Agent消息协议
 *
 * 功能说明:
 * - 定义Agent与Orchestrator之间的消息类型
 * - 支持异步事件通知
 */
public class AgentProtocol {

    private AgentProtocol() {
        // 协议类不实例化
    }

    /**
     * Agent完成消息
     *
     * @param issueId Issue ID
     * @param success 是否成功
     * @param error 错误信息
     */
    public record AgentDone(
            String issueId,
            boolean success,
            String error
    ) {}

    /**
     * Agent心跳消息
     *
     * @param issueId Issue ID
     * @param turnNumber 当前turn编号
     * @param timestamp 时间戳
     */
    public record AgentHeartbeat(
            String issueId,
            int turnNumber,
            Instant timestamp
    ) {}

    /**
     * Agent需要输入消息
     *
     * @param issueId Issue ID
     * @param reason 需要输入的原因
     */
    public record AgentNeedsInput(
            String issueId,
            String reason
    ) {}

    /**
     * 启动Agent命令
     *
     * @param issue 要处理的Issue
     * @param workerHost SSH worker主机
     */
    public record StartAgent(
            Issue issue,
            String workerHost
    ) {}

    /**
     * 停止Agent命令
     *
     * @param issueId Issue ID
     */
    public record StopAgent(
            String issueId
    ) {}
}
