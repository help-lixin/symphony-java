package help.lixin.symphony.config;

import java.util.List;
import java.util.Map;

/**
 * 应用配置数据结构
 *
 * 功能说明:
 * - 包含所有运行时配置项
 * - 从WORKFLOW.md的YAML配置解析
 * - 提供类型安全的配置访问
 *
 * 配置层次:
 * - tracker: 问题追踪器配置
 * - workspace: 工作区配置
 * - agent: Agent执行配置
 * - codex: Agent会话配置 (支持codex/claude/gemini)
 * - hooks: 工作区钩子配置
 * - polling: 轮询配置
 * - observability: 可观测性配置
 * - server: HTTP服务器配置
 */
public class AppConfig {

    private TrackerConfig tracker;
    private WorkspaceConfig workspace;
    private AgentConfig agent;
    private CodexConfig codex;
    private HooksConfig hooks;
    private PollingConfig polling;
    private ObservabilityConfig observability;
    private ServerConfig server;
    private WorkerConfig worker;

    public AppConfig() {
        // 默认构造函数用于反序列化
    }

    // Getter方法

    public TrackerConfig getTracker() {
        return tracker;
    }

    public WorkspaceConfig getWorkspace() {
        return workspace;
    }

    public AgentConfig getAgent() {
        return agent;
    }

    public WorkerConfig getWorker() {
        return worker;
    }

    public CodexConfig getCodex() {
        return codex;
    }

    public HooksConfig getHooks() {
        return hooks;
    }

    public PollingConfig getPolling() {
        return polling;
    }

    public ObservabilityConfig getObservability() {
        return observability;
    }

    public ServerConfig getServer() {
        return server;
    }

    // Setter方法

    public void setTracker(TrackerConfig tracker) {
        this.tracker = tracker;
    }

    public void setWorkspace(WorkspaceConfig workspace) {
        this.workspace = workspace;
    }

    public void setAgent(AgentConfig agent) {
        this.agent = agent;
    }

    public void setCodex(CodexConfig codex) {
        this.codex = codex;
    }

    public void setHooks(HooksConfig hooks) {
        this.hooks = hooks;
    }

    public void setPolling(PollingConfig polling) {
        this.polling = polling;
    }

    public void setObservability(ObservabilityConfig observability) {
        this.observability = observability;
    }

    public void setServer(ServerConfig server) {
        this.server = server;
    }

    /**
     * 追踪器配置
     */
    public static class TrackerConfig {
        private String kind;
        private String endpoint = "https://api.linear.app/graphql";
        private String apiKey;
        private String projectSlug;
        private String assignee;
        private List<String> requiredLabels = List.of();
        private List<String> activeStates = List.of("Todo", "In Progress");
        private List<String> terminalStates = List.of("Closed", "Cancelled", "Canceled", "Duplicate", "Done");

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProjectSlug() { return projectSlug; }
        public void setProjectSlug(String projectSlug) { this.projectSlug = projectSlug; }
        public String getAssignee() { return assignee; }
        public void setAssignee(String assignee) { this.assignee = assignee; }
        public List<String> getRequiredLabels() { return requiredLabels; }
        public void setRequiredLabels(List<String> requiredLabels) { this.requiredLabels = requiredLabels; }
        public List<String> getActiveStates() { return activeStates; }
        public void setActiveStates(List<String> activeStates) { this.activeStates = activeStates; }
        public List<String> getTerminalStates() { return terminalStates; }
        public void setTerminalStates(List<String> terminalStates) { this.terminalStates = terminalStates; }
    }

    /**
     * 工作区配置
     */
    public static class WorkspaceConfig {
        private String root;

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
    }

    /**
     * Agent配置
     */
    public static class AgentConfig {
        private int maxConcurrentAgents = 10;
        private int maxTurns = 20;
        private int maxRetryBackoffMs = 300000;
        private Map<String, Integer> maxConcurrentAgentsByState = Map.of();

        public int getMaxConcurrentAgents() { return maxConcurrentAgents; }
        public void setMaxConcurrentAgents(int maxConcurrentAgents) { this.maxConcurrentAgents = maxConcurrentAgents; }
        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public int getMaxRetryBackoffMs() { return maxRetryBackoffMs; }
        public void setMaxRetryBackoffMs(int maxRetryBackoffMs) { this.maxRetryBackoffMs = maxRetryBackoffMs; }
        public Map<String, Integer> getMaxConcurrentAgentsByState() { return maxConcurrentAgentsByState; }
        public void setMaxConcurrentAgentsByState(Map<String, Integer> maxConcurrentAgentsByState) { this.maxConcurrentAgentsByState = maxConcurrentAgentsByState; }
    }

    /**
     * Agent/Codex配置
     *
     * 支持多种Agent类型:
     * - codex: JSON-RPC 2.0 over stdio
     * - claude: Claude CLI with native protocol
     * - gemini: Gemini CLI with NDJSON streaming
     *
     * 配置示例:
     * codex:
     *   kind: codex  # or "claude", "gemini"
     *   command: codex app-server  # or "claude", "gemini"
     *   model: opus  # for Claude
     *   model: gemini-2.5-pro  # for Gemini
     *   thread_sandbox: workspace-write
     */
    public static class CodexConfig {
        private String kind;  // codex, claude, gemini
        private String command = "codex app-server";
        private Object approvalPolicy;
        private String threadSandbox = "workspace-write";
        private Object turnSandboxPolicy;
        private int turnTimeoutMs = 3600000;
        private int readTimeoutMs = 5000;
        private int stallTimeoutMs = 300000;
        private String promptTemplate;
        
        // Claude-specific config
        private String model = "opus";  // for Claude: opus, sonnet, haiku
        private String fallbackModel;  // fallback Claude model
        private int maxTurns = 20;
        private List<String> allowedTools;
        private List<String> disallowedTools;
        private String mcpConfig;  // MCP server config file path
        
        // Gemini-specific config
        private List<String> allowedDirectories;

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public Object getApprovalPolicy() { return approvalPolicy; }
        public void setApprovalPolicy(Object approvalPolicy) { this.approvalPolicy = approvalPolicy; }
        public String getThreadSandbox() { return threadSandbox; }
        public void setThreadSandbox(String threadSandbox) { this.threadSandbox = threadSandbox; }
        public Object getTurnSandboxPolicy() { return turnSandboxPolicy; }
        public void setTurnSandboxPolicy(Object turnSandboxPolicy) { this.turnSandboxPolicy = turnSandboxPolicy; }
        public int getTurnTimeoutMs() { return turnTimeoutMs; }
        public void setTurnTimeoutMs(int turnTimeoutMs) { this.turnTimeoutMs = turnTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        public int getStallTimeoutMs() { return stallTimeoutMs; }
        public void setStallTimeoutMs(int stallTimeoutMs) { this.stallTimeoutMs = stallTimeoutMs; }
        public String getPromptTemplate() { return promptTemplate; }
        public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }

        // Claude-specific
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getFallbackModel() { return fallbackModel; }
        public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }
        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
        public List<String> getDisallowedTools() { return disallowedTools; }
        public void setDisallowedTools(List<String> disallowedTools) { this.disallowedTools = disallowedTools; }
        public String getMcpConfig() { return mcpConfig; }
        public void setMcpConfig(String mcpConfig) { this.mcpConfig = mcpConfig; }

        // Gemini-specific
        public List<String> getAllowedDirectories() { return allowedDirectories; }
        public void setAllowedDirectories(List<String> allowedDirectories) { this.allowedDirectories = allowedDirectories; }
    }

    /**
     * 钩子配置
     */
    public static class HooksConfig {
        private String afterCreate;
        private String beforeRun;
        private String afterRun;
        private String beforeRemove;
        private int timeoutMs = 60000;

        public String getAfterCreate() { return afterCreate; }
        public void setAfterCreate(String afterCreate) { this.afterCreate = afterCreate; }
        public String getBeforeRun() { return beforeRun; }
        public void setBeforeRun(String beforeRun) { this.beforeRun = beforeRun; }
        public String getAfterRun() { return afterRun; }
        public void setAfterRun(String afterRun) { this.afterRun = afterRun; }
        public String getBeforeRemove() { return beforeRemove; }
        public void setBeforeRemove(String beforeRemove) { this.beforeRemove = beforeRemove; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    /**
     * 轮询配置
     */
    public static class PollingConfig {
        private int intervalMs = 30000;

        public int getIntervalMs() { return intervalMs; }
        public void setIntervalMs(int intervalMs) { this.intervalMs = intervalMs; }
    }

    /**
     * 可观测性配置
     */
    public static class ObservabilityConfig {
        private boolean dashboardEnabled = true;
        private int refreshMs = 1000;
        private int renderIntervalMs = 16;

        public boolean isDashboardEnabled() { return dashboardEnabled; }
        public void setDashboardEnabled(boolean dashboardEnabled) { this.dashboardEnabled = dashboardEnabled; }
        public int getRefreshMs() { return refreshMs; }
        public void setRefreshMs(int refreshMs) { this.refreshMs = refreshMs; }
        public int getRenderIntervalMs() { return renderIntervalMs; }
        public void setRenderIntervalMs(int renderIntervalMs) { this.renderIntervalMs = renderIntervalMs; }
    }

    /**
     * 服务器配置
     */
    public static class ServerConfig {
        private Integer port;
        private String host = "127.0.0.1";

        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
    }
}
