package help.lixin.symphony.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Linear问题数据结构
 *
 * 功能说明:
 * - 表示一个Linear issue的标准化数据结构
 * - 用于Orchestrator和Tracker之间的数据传输
 * - 包含issue的所有关键属性
 *
 * 字段说明:
 * - id: Linear API中的唯一标识符
 * - identifier: 显示标识符（如SYMPHONY-123）
 * - title: issue标题
 * - description: issue描述
 * - priority: 优先级（1-4，1最高）
 * - state: 当前状态（如Todo、In Progress）
 * - branchName: 分支名称
 * - url: Linear中的URL
 * - assigneeId: 分配对象的ID
 * - labels: 标签列表
 * - blockedBy: 阻塞此issue的问题列表
 * - createdAt: 创建时间
 * - updatedAt: 更新时间
 */
public class Issue {
    private final String id;
    private final String identifier;
    private final String title;
    private final String description;
    private final Integer priority;
    private final String state;
    private final String branchName;
    private final String url;
    private final String assigneeId;
    private final List<String> labels;
    private final List<Blocker> blockedBy;
    private final boolean assignedToWorker;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Issue(String id, String identifier, String title, String description,
                 Integer priority, String state, String branchName, String url,
                 String assigneeId, List<String> labels, List<Blocker> blockedBy,
                 boolean assignedToWorker, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.identifier = identifier;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.state = state;
        this.branchName = branchName;
        this.url = url;
        this.assigneeId = assigneeId;
        this.labels = labels != null ? List.copyOf(labels) : List.of();
        this.blockedBy = blockedBy != null ? List.copyOf(blockedBy) : List.of();
        this.assignedToWorker = assignedToWorker;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 获取issue ID
     *
     * @return issue ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取issue标识符
     *
     * @return issue标识符（如SYMPHONY-123）
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * 获取issue标题
     *
     * @return 标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取issue描述
     *
     * @return 描述内容
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取优先级
     *
     * @return 优先级（1-4，1最高）
     */
    public Integer getPriority() {
        return priority;
    }

    /**
     * 获取当前状态
     *
     * @return 状态名称
     */
    public String getState() {
        return state;
    }

    /**
     * 获取分支名称
     *
     * @return 分支名称
     */
    public String getBranchName() {
        return branchName;
    }

    /**
     * 获取URL
     *
     * @return Linear中的URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * 获取分配对象ID
     *
     * @return 分配对象ID
     */
    public String getAssigneeId() {
        return assigneeId;
    }

    /**
     * 获取标签列表
     *
     * @return 标签列表
     */
    public List<String> getLabels() {
        return labels;
    }

    /**
     * 获取阻塞此issue的问题列表
     *
     * @return 阻塞问题列表
     */
    public List<Blocker> getBlockedBy() {
        return blockedBy;
    }

    /**
     * 检查是否分配给worker
     *
     * @return 是否分配
     */
    public boolean isAssignedToWorker() {
        return assignedToWorker;
    }

    /**
     * 获取创建时间
     *
     * @return 创建时间
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取更新时间
     *
     * @return 更新时间
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 检查issue是否可路由
     *
     * @param requiredLabels 必需的标签列表
     * @return 是否可路由
     */
    public boolean isRoutable(List<String> requiredLabels) {
        if (!assignedToWorker) {
            return false;
        }

        if (requiredLabels == null || requiredLabels.isEmpty()) {
            return true;
        }

        List<String> normalizedRequired = requiredLabels.stream()
                .map(String::toLowerCase)
                .map(String::trim)
                .toList();

        List<String> normalizedLabels = labels.stream()
                .map(String::toLowerCase)
                .map(String::trim)
                .toList();

        return normalizedRequired.stream()
                .allMatch(normalizedLabels::contains);
    }

    /**
     * 阻塞问题数据结构
     */
    public static class Blocker {
        private final String id;
        private final String identifier;
        private final String state;

        public Blocker(String id, String identifier, String state) {
            this.id = id;
            this.identifier = identifier;
            this.state = state;
        }

        public String getId() {
            return id;
        }

        public String getIdentifier() {
            return identifier;
        }

        public String getState() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Blocker blocker = (Blocker) o;
            return Objects.equals(id, blocker.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Issue issue = (Issue) o;
        return Objects.equals(id, issue.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Issue{" +
                "id='" + id + '\'' +
                ", identifier='" + identifier + '\'' +
                ", title='" + title + '\'' +
                ", state='" + state + '\'' +
                '}';
    }

    /**
     * Issue构建器
     */
    public static class Builder {
        private String id;
        private String identifier;
        private String title;
        private String description;
        private Integer priority;
        private String state;
        private String branchName;
        private String url;
        private String assigneeId;
        private List<String> labels = List.of();
        private List<Blocker> blockedBy = List.of();
        private boolean assignedToWorker = true;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder identifier(String identifier) {
            this.identifier = identifier;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder priority(Integer priority) {
            this.priority = priority;
            return this;
        }

        public Builder state(String state) {
            this.state = state;
            return this;
        }

        public Builder branchName(String branchName) {
            this.branchName = branchName;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder assigneeId(String assigneeId) {
            this.assigneeId = assigneeId;
            return this;
        }

        public Builder labels(List<String> labels) {
            this.labels = labels;
            return this;
        }

        public Builder blockedBy(List<Blocker> blockedBy) {
            this.blockedBy = blockedBy;
            return this;
        }

        public Builder assignedToWorker(boolean assignedToWorker) {
            this.assignedToWorker = assignedToWorker;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Issue build() {
            return new Issue(id, identifier, title, description, priority, state,
                    branchName, url, assigneeId, labels, blockedBy, assignedToWorker,
                    createdAt, updatedAt);
        }
    }

    /**
     * 创建Builder实例
     *
     * @return 新的Builder
     */
    public static Builder builder() {
        return new Builder();
    }
}
