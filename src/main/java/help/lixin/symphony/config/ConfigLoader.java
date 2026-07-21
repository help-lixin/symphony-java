package help.lixin.symphony.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WORKFLOW.md配置文件加载器
 *
 * 功能说明:
 * - 读取并解析WORKFLOW.md文件
 * - 分离YAML front matter和Markdown body
 * - 支持环境变量插值（如$LINEAR_API_KEY）
 * - 验证配置完整性
 *
 * WORKFLOW.md格式:
 * ---
 * tracker:
 *   kind: linear
 *   project_slug: "..."
 * ---
 * Markdown prompt body
 */
public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    // 环境变量引用模式: $VAR_NAME 或 ${VAR_NAME}
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?");

    // 默认的WORKFLOW.md路径
    private static final String DEFAULT_WORKFLOW_PATH = "WORKFLOW.md";

    /**
     * 加载配置
     *
     * @return 应用配置
     * @throws ConfigLoadException 如果加载失败
     */
    public static AppConfig load() throws ConfigLoadException {
        return load(Path.of(DEFAULT_WORKFLOW_PATH));
    }

    /**
     * 从指定路径加载配置
     *
     * @param workflowPath WORKFLOW.md文件路径
     * @return 应用配置
     * @throws ConfigLoadException 如果加载失败
     */
    public static AppConfig load(Path workflowPath) throws ConfigLoadException {
        logger.info("加载WORKFLOW.md: {}", workflowPath);

        try {
            // 读取文件内容
            String content = Files.readString(workflowPath);

            // 分离front matter和body
            ContentParts parts = splitFrontMatter(content);

            if (parts.frontMatter().isEmpty()) {
                throw new ConfigLoadException("WORKFLOW.md front matter为空或格式不正确");
            }

            // 解析YAML
            AppConfig config = parseYaml(parts.frontMatter());

            // 设置prompt template（markdown body）
            String promptTemplate = parts.body().trim();
            if (!promptTemplate.isEmpty()) {
                config.getCodex().setPromptTemplate(promptTemplate);
            }

            // 应用环境变量插值
            applyEnvironmentVariables(config);

            // 验证配置
            validate(config);

            logger.info("配置加载成功: tracker.kind={}", config.getTracker().getKind());

            return config;

        } catch (IOException e) {
            throw new ConfigLoadException("读取WORKFLOW.md失败: " + workflowPath, e);
        } catch (ConfigValidationException e) {
            throw new ConfigLoadException("配置验证失败: " + e.getMessage(), e);
        }
    }

    /**
     * 分离front matter和body
     *
     * @param content 文件内容
     * @return front matter和body部分
     */
    record ContentParts(String frontMatter, String body) {}

    private static ContentParts splitFrontMatter(String content) {
        String[] lines = content.split("\\R");

        if (lines.length < 2 || !lines[0].equals("---")) {
            // 没有front matter
            return new ContentParts("", content);
        }

        // 找到结束标记
        int endIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].equals("---")) {
                endIndex = i;
                break;
            }
        }

        if (endIndex < 0) {
            return new ContentParts("", content);
        }

        // 提取front matter和body
        String frontMatter = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, endIndex));
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, endIndex + 1, lines.length));

        return new ContentParts(frontMatter, body);
    }

    /**
     * 解析YAML为AppConfig对象
     *
     * @param yamlContent YAML内容
     * @return AppConfig对象
     */
    @SuppressWarnings("unchecked")
    private static AppConfig parseYaml(String yamlContent) {
        LoaderOptions options = new LoaderOptions();
        CustomClassLoaderConstructor constructor = new CustomClassLoaderConstructor(
                AppConfig.class.getClassLoader(), options);

        // 使用自定义PropertyUtils处理下划线命名
        PropertyUtils propertyUtils = new PropertyUtils() {
            @Override
            public Property getProperty(Class<?> type, String name) {
                // 转换下划线命名到驼峰命名
                String camelName = toCamelCase(name);
                return super.getProperty(type, camelName);
            }
        };
        propertyUtils.setSkipMissingProperties(true);
        constructor.setPropertyUtils(propertyUtils);

        Yaml yaml = new Yaml(constructor);

        Map<String, Object> rawConfig = yaml.load(yamlContent);

        if (rawConfig == null) {
            rawConfig = new HashMap<>();
        }

        return mapToConfig(rawConfig);
    }

    /**
     * 将Map转换为AppConfig对象
     *
     * @param map 原始配置Map
     * @return AppConfig对象
     */
    @SuppressWarnings("unchecked")
    private static AppConfig mapToConfig(Map<String, Object> map) {
        AppConfig config = new AppConfig();

        // Tracker配置
        if (map.containsKey("tracker")) {
            config.setTracker(mapToTrackerConfig((Map<String, Object>) map.get("tracker")));
        } else {
            config.setTracker(new AppConfig.TrackerConfig());
        }

        // Workspace配置
        if (map.containsKey("workspace")) {
            config.setWorkspace(mapToWorkspaceConfig((Map<String, Object>) map.get("workspace")));
        } else {
            config.setWorkspace(new AppConfig.WorkspaceConfig());
        }

        // Agent配置
        if (map.containsKey("agent")) {
            config.setAgent(mapToAgentConfig((Map<String, Object>) map.get("agent")));
        } else {
            config.setAgent(new AppConfig.AgentConfig());
        }

        // Codex配置
        if (map.containsKey("codex")) {
            config.setCodex(mapToCodexConfig((Map<String, Object>) map.get("codex")));
        } else {
            config.setCodex(new AppConfig.CodexConfig());
        }

        // Hooks配置
        if (map.containsKey("hooks")) {
            config.setHooks(mapToHooksConfig((Map<String, Object>) map.get("hooks")));
        } else {
            config.setHooks(new AppConfig.HooksConfig());
        }

        // Polling配置
        if (map.containsKey("polling")) {
            config.setPolling(mapToPollingConfig((Map<String, Object>) map.get("polling")));
        } else {
            config.setPolling(new AppConfig.PollingConfig());
        }

        // Observability配置
        if (map.containsKey("observability")) {
            config.setObservability(mapToObservabilityConfig((Map<String, Object>) map.get("observability")));
        } else {
            config.setObservability(new AppConfig.ObservabilityConfig());
        }

        // Server配置
        if (map.containsKey("server")) {
            config.setServer(mapToServerConfig((Map<String, Object>) map.get("server")));
        } else {
            config.setServer(new AppConfig.ServerConfig());
        }

        return config;
    }

    private static AppConfig.TrackerConfig mapToTrackerConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.TrackerConfig();

        AppConfig.TrackerConfig config = new AppConfig.TrackerConfig();
        config.setKind((String) map.get("kind"));
        // Only set endpoint if explicitly provided, otherwise use the default from TrackerConfig
        Object endpointValue = map.get("endpoint");
        if (endpointValue != null) {
            config.setEndpoint((String) endpointValue);
        }
        config.setApiKey(resolveEnvVar((String) map.get("api_key")));
        config.setProjectSlug((String) map.get("project_slug"));
        config.setAssignee(resolveEnvVar((String) map.get("assignee")));

        if (map.get("required_labels") instanceof List) {
            config.setRequiredLabels((List<String>) map.get("required_labels"));
        }
        if (map.get("active_states") instanceof List) {
            config.setActiveStates((List<String>) map.get("active_states"));
        }
        if (map.get("terminal_states") instanceof List) {
            config.setTerminalStates((List<String>) map.get("terminal_states"));
        }

        return config;
    }

    private static AppConfig.WorkspaceConfig mapToWorkspaceConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.WorkspaceConfig();

        AppConfig.WorkspaceConfig config = new AppConfig.WorkspaceConfig();
        String root = (String) map.get("root");

        // 解析环境变量和路径
        if (root != null) {
            root = resolveEnvVar(root);
            // 解析~为用户目录
            if (root.startsWith("~")) {
                root = System.getProperty("user.home") + root.substring(1);
            }
        } else {
            root = System.getProperty("java.io.tmpdir") + "/symphony_workspaces";
        }

        config.setRoot(root);
        return config;
    }

    private static AppConfig.AgentConfig mapToAgentConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.AgentConfig();

        AppConfig.AgentConfig config = new AppConfig.AgentConfig();

        if (map.get("max_concurrent_agents") instanceof Number) {
            config.setMaxConcurrentAgents(((Number) map.get("max_concurrent_agents")).intValue());
        }
        if (map.get("max_turns") instanceof Number) {
            config.setMaxTurns(((Number) map.get("max_turns")).intValue());
        }
        if (map.get("max_retry_backoff_ms") instanceof Number) {
            config.setMaxRetryBackoffMs(((Number) map.get("max_retry_backoff_ms")).intValue());
        }
        if (map.get("max_concurrent_agents_by_state") instanceof Map) {
            Map<String, Object> stateMap = (Map<String, Object>) map.get("max_concurrent_agents_by_state");
            Map<String, Integer> converted = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : stateMap.entrySet()) {
                if (entry.getValue() instanceof Number) {
                    converted.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                }
            }
            config.setMaxConcurrentAgentsByState(converted);
        }

        return config;
    }

    private static AppConfig.CodexConfig mapToCodexConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.CodexConfig();

        AppConfig.CodexConfig config = new AppConfig.CodexConfig();
        config.setCommand((String) map.get("command"));
        config.setApprovalPolicy(map.get("approval_policy"));
        config.setThreadSandbox((String) map.get("thread_sandbox"));
        config.setTurnSandboxPolicy(map.get("turn_sandbox_policy"));

        if (map.get("turn_timeout_ms") instanceof Number) {
            config.setTurnTimeoutMs(((Number) map.get("turn_timeout_ms")).intValue());
        }
        if (map.get("read_timeout_ms") instanceof Number) {
            config.setReadTimeoutMs(((Number) map.get("read_timeout_ms")).intValue());
        }
        if (map.get("stall_timeout_ms") instanceof Number) {
            config.setStallTimeoutMs(((Number) map.get("stall_timeout_ms")).intValue());
        }

        return config;
    }

    private static AppConfig.HooksConfig mapToHooksConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.HooksConfig();

        AppConfig.HooksConfig config = new AppConfig.HooksConfig();
        config.setAfterCreate((String) map.get("after_create"));
        config.setBeforeRun((String) map.get("before_run"));
        config.setAfterRun((String) map.get("after_run"));
        config.setBeforeRemove((String) map.get("before_remove"));

        if (map.get("timeout_ms") instanceof Number) {
            config.setTimeoutMs(((Number) map.get("timeout_ms")).intValue());
        }

        return config;
    }

    private static AppConfig.PollingConfig mapToPollingConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.PollingConfig();

        AppConfig.PollingConfig config = new AppConfig.PollingConfig();
        if (map.get("interval_ms") instanceof Number) {
            config.setIntervalMs(((Number) map.get("interval_ms")).intValue());
        }

        return config;
    }

    private static AppConfig.ObservabilityConfig mapToObservabilityConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.ObservabilityConfig();

        AppConfig.ObservabilityConfig config = new AppConfig.ObservabilityConfig();
        config.setDashboardEnabled(map.getOrDefault("dashboard_enabled", true) == Boolean.TRUE);

        if (map.get("refresh_ms") instanceof Number) {
            config.setRefreshMs(((Number) map.get("refresh_ms")).intValue());
        }
        if (map.get("render_interval_ms") instanceof Number) {
            config.setRenderIntervalMs(((Number) map.get("render_interval_ms")).intValue());
        }

        return config;
    }

    private static AppConfig.ServerConfig mapToServerConfig(Map<String, Object> map) {
        if (map == null) return new AppConfig.ServerConfig();

        AppConfig.ServerConfig config = new AppConfig.ServerConfig();
        config.setPort(map.get("port") instanceof Number ? ((Number) map.get("port")).intValue() : null);
        config.setHost((String) map.get("host"));

        return config;
    }

    /**
     * 解析环境变量引用
     * 支持 $VAR_NAME 和 ${VAR_NAME} 格式
     *
     * @param value 包含环境变量引用的字符串
     * @return 解析后的字符串
     */
    private static String resolveEnvVar(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String envName = matcher.group(1);
            String envValue = System.getenv(envName);
            if (envValue != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(envValue));
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 应用环境变量插值到所有配置
     *
     * @param config 配置对象
     */
    @SuppressWarnings("unchecked")
    private static void applyEnvironmentVariables(AppConfig config) {
        // API Key
        AppConfig.TrackerConfig tracker = config.getTracker();
        if (tracker.getApiKey() != null && tracker.getApiKey().startsWith("$")) {
            String resolved = resolveEnvVar(tracker.getApiKey());
            tracker.setApiKey(resolved);
        }
        if (tracker.getAssignee() != null && tracker.getAssignee().startsWith("$")) {
            String resolved = resolveEnvVar(tracker.getAssignee());
            tracker.setAssignee(resolved);
        }

        // Workspace root
        AppConfig.WorkspaceConfig workspace = config.getWorkspace();
        if (workspace.getRoot() != null && workspace.getRoot().startsWith("$")) {
            String resolved = resolveEnvVar(workspace.getRoot());
            if (resolved.startsWith("~")) {
                resolved = System.getProperty("user.home") + resolved.substring(1);
            }
            workspace.setRoot(resolved);
        }
    }

    /**
     * 验证配置完整性
     *
     * @param config 配置对象
     * @throws ConfigValidationException 如果验证失败
     */
    private static void validate(AppConfig config) throws ConfigValidationException {
        AppConfig.TrackerConfig tracker = config.getTracker();

        // 验证tracker kind
        if (tracker.getKind() == null || tracker.getKind().isBlank()) {
            throw new ConfigValidationException("tracker.kind未设置");
        }

        // 验证Linear必需配置
        if ("linear".equalsIgnoreCase(tracker.getKind())) {
            if (tracker.getApiKey() == null || tracker.getApiKey().isBlank()) {
                throw new ConfigValidationException("Linear API key未设置 (tracker.api_key 或 LINEAR_API_KEY)");
            }
            if (tracker.getProjectSlug() == null || tracker.getProjectSlug().isBlank()) {
                throw new ConfigValidationException("Linear project slug未设置 (tracker.project_slug)");
            }
        }

        // 验证工作区根目录
        if (config.getWorkspace().getRoot() == null || config.getWorkspace().getRoot().isBlank()) {
            throw new ConfigValidationException("workspace.root未设置");
        }
    }

    /**
     * 下划线命名转驼峰命名
     */
    private static String toCamelCase(String snakeCase) {
        if (snakeCase == null || !snakeCase.contains("_")) {
            return snakeCase;
        }

        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;

        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * 配置加载异常
     */
    public static class ConfigLoadException extends Exception {
        public ConfigLoadException(String message) {
            super(message);
        }

        public ConfigLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 配置验证异常
     */
    public static class ConfigValidationException extends Exception {
        public ConfigValidationException(String message) {
            super(message);
        }
    }
}
