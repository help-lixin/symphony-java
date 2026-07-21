package help.lixin.symphony.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AppConfig配置类测试
 */
class AppConfigTest {

    @Test
    void testDefaultConstructor() {
        AppConfig config = new AppConfig();
        assertNotNull(config);
    }

    @Test
    void testTrackerConfig() {
        AppConfig.TrackerConfig tracker = new AppConfig.TrackerConfig();
        tracker.setKind("linear");
        tracker.setApiKey("test-key");
        tracker.setProjectSlug("test-project");

        assertEquals("linear", tracker.getKind());
        assertEquals("test-key", tracker.getApiKey());
        assertEquals("test-project", tracker.getProjectSlug());
    }

    @Test
    void testWorkspaceConfig() {
        AppConfig.WorkspaceConfig workspace = new AppConfig.WorkspaceConfig();
        workspace.setRoot("/tmp/workspaces");

        assertEquals("/tmp/workspaces", workspace.getRoot());
    }

    @Test
    void testAgentConfig() {
        AppConfig.AgentConfig agent = new AppConfig.AgentConfig();
        agent.setMaxConcurrentAgents(5);
        agent.setMaxTurns(10);
        agent.setMaxRetryBackoffMs(60000);
        agent.setMaxConcurrentAgentsByState(Map.of("running", 3));

        assertEquals(5, agent.getMaxConcurrentAgents());
        assertEquals(10, agent.getMaxTurns());
        assertEquals(60000, agent.getMaxRetryBackoffMs());
        assertEquals(3, agent.getMaxConcurrentAgentsByState().get("running"));
    }

    @Test
    void testCodexConfig() {
        AppConfig.CodexConfig codex = new AppConfig.CodexConfig();
        codex.setCommand("codex app-server");
        codex.setThreadSandbox("workspace-write");
        codex.setTurnTimeoutMs(3600000);
        codex.setPromptTemplate("Test prompt template");

        assertEquals("codex app-server", codex.getCommand());
        assertEquals("workspace-write", codex.getThreadSandbox());
        assertEquals(3600000, codex.getTurnTimeoutMs());
        assertEquals("Test prompt template", codex.getPromptTemplate());
    }

    @Test
    void testHooksConfig() {
        AppConfig.HooksConfig hooks = new AppConfig.HooksConfig();
        hooks.setAfterCreate("echo created");
        hooks.setBeforeRun("echo before run");
        hooks.setAfterRun("echo after run");
        hooks.setBeforeRemove("echo before remove");
        hooks.setTimeoutMs(60000);

        assertEquals("echo created", hooks.getAfterCreate());
        assertEquals("echo before run", hooks.getBeforeRun());
        assertEquals("echo after run", hooks.getAfterRun());
        assertEquals("echo before remove", hooks.getBeforeRemove());
        assertEquals(60000, hooks.getTimeoutMs());
    }

    @Test
    void testPollingConfig() {
        AppConfig.PollingConfig polling = new AppConfig.PollingConfig();
        polling.setIntervalMs(30000);

        assertEquals(30000, polling.getIntervalMs());
    }

    @Test
    void testObservabilityConfig() {
        AppConfig.ObservabilityConfig observability = new AppConfig.ObservabilityConfig();
        observability.setRefreshMs(1000);
        observability.setRenderIntervalMs(500);

        assertEquals(1000, observability.getRefreshMs());
        assertEquals(500, observability.getRenderIntervalMs());
    }

    @Test
    void testServerConfig() {
        AppConfig.ServerConfig server = new AppConfig.ServerConfig();
        server.setPort(8080);

        assertEquals(8080, server.getPort());
    }

    @Test
    void testWorkerConfig() {
        WorkerConfig worker = new WorkerConfig();
        worker.setSshHosts(List.of("host1", "host2"));

        assertEquals(2, worker.getSshHosts().size());
        assertEquals("host1", worker.getSshHosts().get(0));
        assertEquals("host2", worker.getSshHosts().get(1));
    }

    @Test
    void testFullConfig() {
        // 测试完整配置
        AppConfig config = new AppConfig();

        AppConfig.TrackerConfig tracker = new AppConfig.TrackerConfig();
        tracker.setKind("linear");
        config.setTracker(tracker);

        AppConfig.WorkspaceConfig workspace = new AppConfig.WorkspaceConfig();
        workspace.setRoot("/tmp");
        config.setWorkspace(workspace);

        AppConfig.AgentConfig agent = new AppConfig.AgentConfig();
        agent.setMaxConcurrentAgents(10);
        config.setAgent(agent);

        AppConfig.CodexConfig codex = new AppConfig.CodexConfig();
        codex.setCommand("codex");
        config.setCodex(codex);

        assertNotNull(config.getTracker());
        assertNotNull(config.getWorkspace());
        assertNotNull(config.getAgent());
        assertNotNull(config.getCodex());
        assertEquals(10, config.getAgent().getMaxConcurrentAgents());
    }
}
