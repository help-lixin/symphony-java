package help.lixin.symphony.workspace;

import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import help.lixin.symphony.util.PathSafety;
import help.lixin.symphony.util.SSH;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作区管理器
 *
 * 功能说明:
 * - 为每个Issue创建和管理隔离的工作区
 * - 执行工作区生命周期钩子
 * - 支持本地和SSH远程工作区
 * - 验证路径安全性
 *
 * 路径结构:
 * workspace.root/issue_identifier_safe/
 *
 * 钩子执行顺序:
 * 1. after_create: 工作区创建后执行（通常用于git clone）
 * 2. before_run: Agent运行前执行
 * 3. after_run: Agent运行后执行
 * 4. before_remove: 工作区删除前执行
 */
public class WorkspaceManager {
    private static final Logger logger = LoggerFactory.getLogger(WorkspaceManager.class);

    private final AppConfig config;
    private final String workspaceRoot;
    private final int hookTimeoutMs;

    public WorkspaceManager(AppConfig config) {
        this.config = config;
        this.workspaceRoot = resolveWorkspaceRoot(config);
        this.hookTimeoutMs = config.getHooks().getTimeoutMs();
    }

    /**
     * 解析工作区根目录
     */
    private String resolveWorkspaceRoot(AppConfig config) {
        String root = config.getWorkspace().getRoot();

        // 解析~为用户目录
        if (root != null && root.startsWith("~")) {
            root = System.getProperty("user.home") + root.substring(1);
        }

        // 解析环境变量
        root = resolveEnvVar(root);

        return root;
    }

    private String resolveEnvVar(String value) {
        if (value == null) return null;
        Pattern pattern = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?");
        return pattern.matcher(value).replaceAll(match -> {
            String envName = match.group(1);
            return System.getenv(envName) != null ? System.getenv(envName) : match.group();
        });
    }

    /**
     * 为Issue创建工作区
     *
     * @param issue Issue对象
     * @param workerHost SSH worker主机（null表示本地）
     * @return 工作区路径
     */
    public Path createForIssue(Issue issue, String workerHost) throws WorkspaceException {
        String safeId = safeIdentifier(issue.getIdentifier());
        Path workspacePath = Path.of(workspaceRoot, safeId);

        logger.info("创建工作区: issue={}, workspace={}, worker={}",
                issue.getIdentifier(), workspacePath, workerHost != null ? workerHost : "local");

        try {
            if (workerHost == null) {
                return createLocalWorkspace(workspacePath, issue);
            } else {
                return createRemoteWorkspace(workspacePath, issue, workerHost);
            }
        } catch (Exception e) {
            throw new WorkspaceException("创建工作区失败: " + issue.getIdentifier(), e);
        }
    }

    /**
     * 创建本地工作区
     */
    private Path createLocalWorkspace(Path workspacePath, Issue issue) throws IOException {
        // 如果已存在，删除并重建
        if (Files.exists(workspacePath)) {
            if (Files.isDirectory(workspacePath)) {
                deleteDirectory(workspacePath);
            } else {
                Files.delete(workspacePath);
            }
        }

        Files.createDirectories(workspacePath);

        // 运行after_create钩子
        if (config.getHooks().getAfterCreate() != null) {
            runHook(config.getHooks().getAfterCreate(), workspacePath, issue, "after_create", null);
        }

        return workspacePath;
    }

    /**
     * 创建远程工作区
     */
    private Path createRemoteWorkspace(Path workspacePath, Issue issue, String workerHost)
            throws TimeoutException, SSH.SSHException, WorkspaceException {
        String script = buildRemoteCreateScript(workspacePath);

        SSH.Result result = SSH.run(workerHost, script, hookTimeoutMs, TimeUnit.MILLISECONDS);

        if (result.exitCode() != 0) {
            throw new WorkspaceException("远程工作区创建失败: exitCode=" + result.exitCode());
        }

        // 运行after_create钩子
        if (config.getHooks().getAfterCreate() != null) {
            runHook(config.getHooks().getAfterCreate(), workspacePath, issue, "after_create", workerHost);
        }

        return workspacePath;
    }

    /**
     * 构建远程创建脚本
     */
    private String buildRemoteCreateScript(Path workspace) {
        return String.format("""
                set -eu
                workspace=%s
                if [ -d "$workspace" ]; then
                  created=0
                elif [ -e "$workspace" ]; then
                  rm -rf "$workspace"
                  mkdir -p "$workspace"
                  created=1
                else
                  mkdir -p "$workspace"
                  created=1
                fi
                cd "$workspace"
                printf '%%s\\t%%s\\t%%s\\n' '__SYMPHONY_WORKSPACE__' "$created" "$(pwd -P)"
                """,
                shellEscape(workspace.toString()));
    }

    /**
     * 删除工作区
     *
     * @param workspacePath 工作区路径
     * @param workerHost SSH worker主机（null表示本地）
     */
    public void remove(Path workspacePath, String workerHost) throws WorkspaceException {
        logger.info("删除工作区: workspace={}, worker={}",
                workspacePath, workerHost != null ? workerHost : "local");

        try {
            if (workerHost == null) {
                removeLocalWorkspace(workspacePath);
            } else {
                removeRemoteWorkspace(workspacePath, workerHost);
            }
        } catch (Exception e) {
            throw new WorkspaceException("删除工作区失败: " + workspacePath, e);
        }
    }

    /**
     * 删除本地工作区
     */
    private void removeLocalWorkspace(Path workspacePath) throws IOException {
        if (!Files.exists(workspacePath)) {
            return;
        }

        // 运行before_remove钩子
        if (config.getHooks().getBeforeRemove() != null) {
            Issue placeholderIssue = Issue.builder()
                    .identifier(workspacePath.getFileName().toString())
                    .build();
            runHook(config.getHooks().getBeforeRemove(), workspacePath, placeholderIssue, "before_remove", null);
        }

        deleteDirectory(workspacePath);
    }

    /**
     * 删除远程工作区
     */
    private void removeRemoteWorkspace(Path workspacePath, String workerHost)
            throws TimeoutException, SSH.SSHException {
        // 运行before_remove钩子
        if (config.getHooks().getBeforeRemove() != null) {
            Issue placeholderIssue = Issue.builder()
                    .identifier(workspacePath.getFileName().toString())
                    .build();
            runHook(config.getHooks().getBeforeRemove(), workspacePath, placeholderIssue, "before_remove", workerHost);
        }

        String script = String.format("rm -rf %s", shellEscape(workspacePath.toString()));
        SSH.run(workerHost, script, hookTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 运行工作区钩子
     *
     * @param command 钩子命令
     * @param workspace 工作区路径
     * @param issue Issue对象
     * @param hookName 钩子名称
     * @param workerHost SSH worker主机
     */
    public void runHook(String command, Path workspace, Issue issue, String hookName, String workerHost) {
        if (command == null || command.isBlank()) {
            return;
        }

        logger.info("运行钩子: hook={}, workspace={}, worker={}",
                hookName, workspace, workerHost != null ? workerHost : "local");

        try {
            if (workerHost == null) {
                runLocalHook(command, workspace);
            } else {
                runRemoteHook(command, workspace, workerHost);
            }
        } catch (Exception e) {
            logger.warn("钩子执行失败: hook={}, error={}", hookName, e.getMessage());
            // 钩子失败不抛出异常，仅记录警告
        }
    }

    /**
     * 运行本地钩子
     */
    private void runLocalHook(String command, Path workspace) throws IOException, InterruptedException, TimeoutException {
        logger.info("执行钩子命令: bash -lc \"{}\"", command);
        logger.info("工作目录: {}", workspace.toAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 消费stdout防止缓冲区满导致进程阻塞
        StringBuilder output = new StringBuilder();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append("\n");
                    // 钩子输出较多时只保留最后100行
                    if (output.length() > 10000) {
                        output.delete(0, output.length() - 5000);
                    }
                }
            } catch (IOException e) {
                logger.debug("读取钩子输出时出错: {}", e.getMessage());
            }
        });
        readerThread.start();

        if (!process.waitFor(hookTimeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            readerThread.interrupt();
            logger.warn("钩子执行超时: command={}, timeoutMs={}", command, hookTimeoutMs);
            throw new TimeoutException("钩子执行超时");
        }

        readerThread.join(5000); // 等待最多5秒让reader结束

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorOutput = output.toString();
            logger.warn("钩子执行失败: exitCode={}, output={}", exitCode, errorOutput);
            throw new RuntimeException("钩子执行失败: exitCode=" + exitCode);
        }

        logger.info("钩子执行成功: command={}, output长度={}", command, output.length());
    }

    /**
     * 运行远程钩子
     */
    private void runRemoteHook(String command, Path workspace, String workerHost)
            throws TimeoutException, SSH.SSHException {
        String fullCommand = String.format("cd %s && %s", shellEscape(workspace.toString()), command);
        SSH.Result result = SSH.run(workerHost, fullCommand, hookTimeoutMs, TimeUnit.MILLISECONDS);

        if (result.exitCode() != 0) {
            throw new RuntimeException("远程钩子执行失败: exitCode=" + result.exitCode());
        }
    }

    /**
     * 验证工作区路径安全性
     *
     * @param workspacePath 工作区路径
     * @param workerHost SSH worker主机
     * @return 是否安全
     */
    public boolean validateWorkspacePath(Path workspacePath, String workerHost) {
        if (workerHost != null) {
            // 远程工作区仅做基本验证
            return workspacePath != null &&
                    !workspacePath.toString().isBlank() &&
                    !workspacePath.toString().contains("\n");
        }

        // 本地工作区需要验证路径安全
        try {
            Path expanded = workspacePath.toAbsolutePath().normalize();
            Path root = Path.of(workspaceRoot).toAbsolutePath().normalize();

            // 不能等于根目录
            if (expanded.equals(root)) {
                logger.warn("工作区路径不能等于根目录");
                return false;
            }

            // 必须在根目录下
            if (!expanded.startsWith(root)) {
                logger.warn("工作区路径在根目录之外");
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.warn("路径验证失败", e);
            return false;
        }
    }

    /**
     * 生成安全的问题标识符
     */
    private String safeIdentifier(String identifier) {
        if (identifier == null) {
            return "issue";
        }
        return identifier.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Shell转义
     */
    private String shellEscape(String value) {
        if (value == null) return "";
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteDirectory(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * 获取工作区根目录
     */
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 工作区异常
     */
    public static class WorkspaceException extends Exception {
        public WorkspaceException(String message) {
            super(message);
        }

        public WorkspaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
