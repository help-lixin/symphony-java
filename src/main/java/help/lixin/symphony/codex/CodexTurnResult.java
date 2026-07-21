package help.lixin.symphony.codex;

import java.time.Instant;
import java.util.Map;

/**
 * Codex Turn执行结果
 *
 * 功能说明:
 * - 封装Codex turn执行的结果
 * - 包含完成状态、token使用、会话信息等
 *
 * 字段说明:
 * - completed: 是否正常完成
 * - error: 错误信息（如果失败）
 * - sessionId: 会话ID
 * - threadId: 线程ID
 * - turnId: Turn ID
 * - inputTokens: 输入token数
 * - outputTokens: 输出token数
 * - totalTokens: 总token数
 * - usage: 详细使用信息
 */
public class CodexTurnResult {
    private final boolean completed;
    private final String error;
    private final String sessionId;
    private final String threadId;
    private final String turnId;
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final Instant completedAt;
    private final Map<String, Object> usage;

    private CodexTurnResult(Builder builder) {
        this.completed = builder.completed;
        this.error = builder.error;
        this.sessionId = builder.sessionId;
        this.threadId = builder.threadId;
        this.turnId = builder.turnId;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.totalTokens = builder.totalTokens;
        this.completedAt = builder.completedAt;
        this.usage = builder.usage;
    }

    public boolean isCompleted() {
        return completed;
    }

    public String getError() {
        return error;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getTurnId() {
        return turnId;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Map<String, Object> getUsage() {
        return usage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean completed;
        private String error;
        private String sessionId;
        private String threadId;
        private String turnId;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private Instant completedAt = Instant.now();
        private Map<String, Object> usage;

        public Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder threadId(String threadId) {
            this.threadId = threadId;
            return this;
        }

        public Builder turnId(String turnId) {
            this.turnId = turnId;
            return this;
        }

        public Builder inputTokens(long inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        public Builder outputTokens(long outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        public Builder totalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder usage(Map<String, Object> usage) {
            this.usage = usage;
            return this;
        }

        public CodexTurnResult build() {
            return new CodexTurnResult(this);
        }
    }

    @Override
    public String toString() {
        return "CodexTurnResult{" +
                "completed=" + completed +
                ", sessionId='" + sessionId + '\'' +
                ", turnId='" + turnId + '\'' +
                ", totalTokens=" + totalTokens +
                '}';
    }
}
