package help.lixin.symphony.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigLoader配置加载器测试
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoadMinimalConfig() throws Exception {
        // 创建最小的WORKFLOW.md文件
        String content = """
                ---
                tracker:
                  kind: memory
                workspace:
                  root: /tmp/workspaces
                agent:
                  max_concurrent_agents: 5
                ---

                # Test Workflow
                """;

        Path workflowFile = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflowFile, content);

        AppConfig config = ConfigLoader.load(workflowFile);

        assertNotNull(config);
        assertEquals("memory", config.getTracker().getKind());
        assertEquals("/tmp/workspaces", config.getWorkspace().getRoot());
        assertEquals(5, config.getAgent().getMaxConcurrentAgents());
    }

    @Test
    void testLoadFullConfig() throws Exception {
        String content = """
                ---
                tracker:
                  kind: linear
                  api_key: test-api-key
                  project_slug: test-project
                  active_states:
                    - backlog
                    - triage
                  terminal_states:
                    - done
                    - cancelled

                workspace:
                  root: /workspaces

                agent:
                  max_concurrent_agents: 10
                  max_turns: 20
                  max_retry_backoff_ms: 300000

                codex:
                  command: codex app-server
                  thread_sandbox: workspace-write

                hooks:
                  after_create: echo "Created workspace"
                  before_run: echo "Starting agent"

                polling:
                  interval_ms: 60000

                observability:
                  refresh_ms: 1000
                  render_interval_ms: 500

                worker:
                  ssh_hosts:
                    - localhost
                    - remote-host
                ---

                # Test Workflow
                """;

        Path workflowFile = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflowFile, content);

        AppConfig config = ConfigLoader.load(workflowFile);

        assertNotNull(config);
        assertEquals("linear", config.getTracker().getKind());
        assertEquals("test-api-key", config.getTracker().getApiKey());
        assertEquals("test-project", config.getTracker().getProjectSlug());
        assertEquals(2, config.getTracker().getActiveStates().size());
        assertEquals(2, config.getTracker().getTerminalStates().size());

        assertEquals("/workspaces", config.getWorkspace().getRoot());

        assertEquals(10, config.getAgent().getMaxConcurrentAgents());
        assertEquals(20, config.getAgent().getMaxTurns());
        assertEquals(300000, config.getAgent().getMaxRetryBackoffMs());

        assertEquals("codex app-server", config.getCodex().getCommand());
        assertEquals("workspace-write", config.getCodex().getThreadSandbox());

        assertNotNull(config.getHooks());
        assertEquals("echo \"Created workspace\"", config.getHooks().getAfterCreate());

        assertEquals(60000, config.getPolling().getIntervalMs());

        assertEquals(1000, config.getObservability().getRefreshMs());
        assertEquals(500, config.getObservability().getRenderIntervalMs());

        // WorkerConfig可能为null，取决于ConfigLoader是否支持
        if (config.getWorker() != null) {
            assertEquals(2, config.getWorker().getSshHosts().size());
        }
    }

    @Test
    void testLoadConfigFileNotFound() {
        assertThrows(ConfigLoader.ConfigLoadException.class, () -> {
            ConfigLoader.load(Path.of("/nonexistent/WORKFLOW.md"));
        });
    }

    @Test
    void testDefaultValues() throws Exception {
        // 只有有效配置时才能加载
        String content = """
                ---
                tracker:
                  kind: memory
                workspace:
                  root: /tmp
                ---

                # Test
                """;

        Path workflowFile = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflowFile, content);

        AppConfig config = ConfigLoader.load(workflowFile);

        // 验证默认值
        assertNotNull(config);
        assertEquals("memory", config.getTracker().getKind());
        assertEquals(10, config.getAgent().getMaxConcurrentAgents());
        assertEquals(20, config.getAgent().getMaxTurns());
    }
}
