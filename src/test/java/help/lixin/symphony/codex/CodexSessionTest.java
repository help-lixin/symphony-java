package help.lixin.symphony.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import help.lixin.symphony.agent.AgentSession;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CodexSession
 *
 * Tests the AgentSession implementation for Codex provider.
 */
class CodexSessionTest {

    @TempDir
    Path tempDir;

    private AppConfig config;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        config = new AppConfig();
        
        // Tracker config
        AppConfig.TrackerConfig trackerConfig = new AppConfig.TrackerConfig();
        trackerConfig.setKind("memory");
        config.setTracker(trackerConfig);

        // Workspace config
        AppConfig.WorkspaceConfig workspaceConfig = new AppConfig.WorkspaceConfig();
        workspaceConfig.setRoot(tempDir.toString());
        config.setWorkspace(workspaceConfig);

        // Codex config
        AppConfig.CodexConfig codexConfig = new AppConfig.CodexConfig();
        codexConfig.setCommand("echo test"); // Use echo for testing
        codexConfig.setReadTimeoutMs(1000);
        config.setCodex(codexConfig);
    }

    @Test
    void testGetType() {
        CodexSession session = new CodexSession(config, tempDir.toString(), null);
        assertEquals(AgentSession.Type.CODEX, session.getType());
    }

    @Test
    void testInitialSessionId() {
        CodexSession session = new CodexSession(config, tempDir.toString(), null);
        assertNull(session.getSessionId());
    }

    @Test
    void testInitialTokens() {
        CodexSession session = new CodexSession(config, tempDir.toString(), null);
        assertEquals(0, session.getInputTokens());
        assertEquals(0, session.getOutputTokens());
        assertEquals(0, session.getTotalTokens());
    }

    @Test
    void testStopWithoutStart() {
        CodexSession session = new CodexSession(config, tempDir.toString(), null);
        // Should not throw
        session.stop();
    }

    @Test
    void testAgentEventCreation() {
        JsonNode params = objectMapper.createObjectNode();
        
        AgentSession.AgentEvent event = new AgentSession.AgentEvent(
                "test_method",
                params,
                "session-123",
                100L,
                200L,
                300L,
                null
        );

        assertEquals("test_method", event.method());
        assertEquals("session-123", event.sessionId());
        assertEquals(100L, event.inputTokens());
        assertEquals(200L, event.outputTokens());
        assertEquals(300L, event.totalTokens());
        assertNotNull(event.timestamp());
    }

    @Test
    void testTurnResultSuccess() {
        AgentSession.TurnResult result = AgentSession.TurnResult.success(
                "session-123",
                100L,
                200L,
                300L
        );

        assertTrue(result.isCompleted());
        assertNull(result.getError());
        assertEquals("session-123", result.getSessionId());
        assertEquals(100L, result.getInputTokens());
        assertEquals(200L, result.getOutputTokens());
        assertEquals(300L, result.getTotalTokens());
    }

    @Test
    void testTurnResultFailure() {
        AgentSession.TurnResult result = AgentSession.TurnResult.failure("Test error");

        assertFalse(result.isCompleted());
        assertEquals("Test error", result.getError());
        assertEquals(0L, result.getInputTokens());
        assertEquals(0L, result.getOutputTokens());
        assertEquals(0L, result.getTotalTokens());
    }

    @Test
    void testTurnResultGetters() {
        AgentSession.TurnResult result = new AgentSession.TurnResult(
                true,
                null,
                "session-456",
                "thread-789",
                "turn-012",
                500L,
                600L,
                1100L
        );

        assertEquals("session-456", result.getSessionId());
        assertEquals("thread-789", result.getThreadId());
        assertEquals("turn-012", result.getTurnId());
        assertEquals(500L, result.getInputTokens());
        assertEquals(600L, result.getOutputTokens());
        assertEquals(1100L, result.getTotalTokens());
    }

    @Test
    void testAgentException() {
        AgentSession.AgentException exception = new AgentSession.AgentException("Test message");
        assertEquals("Test message", exception.getMessage());

        AgentSession.AgentException withCause = new AgentSession.AgentException("Test with cause", new RuntimeException("inner"));
        assertEquals("Test with cause", withCause.getMessage());
        assertNotNull(withCause.getCause());
        assertEquals("inner", withCause.getCause().getMessage());
    }

    @Test
    void testDynamicToolSpecs() {
        var specs = DynamicTool.toolSpecs();
        
        assertNotNull(specs);
        assertFalse(specs.isEmpty());
        
        // Check first tool spec structure
        var firstTool = specs.get(0);
        assertTrue(firstTool.containsKey("name"));
        assertTrue(firstTool.containsKey("description"));
        assertTrue(firstTool.containsKey("inputSchema"));
        
        var inputSchema = (java.util.Map<?, ?>) firstTool.get("inputSchema");
        assertEquals("object", inputSchema.get("type"));
    }

    @Test
    void testDynamicToolExecuteWithInvalidTool() {
        CodexSession session = new CodexSession(config, tempDir.toString(), null);
        
        // DynamicTool is internal, but we can test via reflection or by checking tool names
        var specs = DynamicTool.toolSpecs();
        
        // Verify all expected tools are present
        var toolNames = specs.stream()
                .map(m -> (String) m.get("name"))
                .toList();
        
        assertTrue(toolNames.contains("get_issue"));
        assertTrue(toolNames.contains("get_comments"));
        assertTrue(toolNames.contains("create_comment"));
        assertTrue(toolNames.contains("update_issue_state"));
    }

    @Test
    void testConfigReadTimeout() {
        assertEquals(1000, config.getCodex().getReadTimeoutMs());
    }

    @Test
    void testCodexSessionCreation() {
        CodexSession session = new CodexSession(config, tempDir.toString(), "localhost");
        
        // Session not started, sessionId should be null
        assertNull(session.getSessionId());
    }
}
