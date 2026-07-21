package help.lixin.symphony.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import help.lixin.symphony.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClaudeSession
 *
 * Tests the AgentSession implementation for Claude provider.
 */
class ClaudeSessionTest {

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

        // Codex config (used for agent settings)
        AppConfig.CodexConfig codexConfig = new AppConfig.CodexConfig();
        codexConfig.setCommand("claude");
        codexConfig.setModel("sonnet");  // Use faster model for testing
        codexConfig.setMaxTurns(10);
        config.setCodex(codexConfig);
    }

    @Test
    void testGetType() {
        ClaudeSession session = new ClaudeSession(config, tempDir.toString(), null);
        assertEquals(AgentSession.Type.CLAUDE, session.getType());
    }

    @Test
    void testInitialSessionId() {
        ClaudeSession session = new ClaudeSession(config, tempDir.toString(), null);
        assertNull(session.getSessionId());
    }

    @Test
    void testInitialTokens() {
        ClaudeSession session = new ClaudeSession(config, tempDir.toString(), null);
        assertEquals(0, session.getInputTokens());
        assertEquals(0, session.getOutputTokens());
        assertEquals(0, session.getTotalTokens());
    }

    @Test
    void testStopWithoutStart() {
        ClaudeSession session = new ClaudeSession(config, tempDir.toString(), null);
        // Should not throw
        session.stop();
    }

    @Test
    void testModelFromConfig() {
        ClaudeSession session = new ClaudeSession(config, tempDir.toString(), null);
        // Model is loaded from config, verify it's set
        assertNotNull(config.getCodex().getModel());
        assertEquals("sonnet", config.getCodex().getModel());
    }

    @Test
    void testMaxTurnsFromConfig() {
        assertEquals(10, config.getCodex().getMaxTurns());
    }

    @Test
    void testAgentEventCreation() {
        JsonNode params = objectMapper.createObjectNode();
        
        AgentSession.AgentEvent event = new AgentSession.AgentEvent(
                "result",
                params,
                "claude-session-123",
                150L,
                250L,
                400L,
                null
        );

        assertEquals("result", event.method());
        assertEquals("claude-session-123", event.sessionId());
        assertEquals(150L, event.inputTokens());
        assertEquals(250L, event.outputTokens());
        assertEquals(400L, event.totalTokens());
        assertNotNull(event.timestamp());
    }

    @Test
    void testTurnResultWithClaudeData() {
        AgentSession.TurnResult result = AgentSession.TurnResult.success(
                "claude-session-456",
                500L,
                800L,
                1300L
        );

        assertTrue(result.isCompleted());
        assertNull(result.getError());
        assertEquals("claude-session-456", result.getSessionId());
        assertEquals(500L, result.getInputTokens());
        assertEquals(800L, result.getOutputTokens());
        assertEquals(1300L, result.getTotalTokens());
    }

    @Test
    void testClaudeException() {
        AgentSession.AgentException exception = new AgentSession.AgentException("Claude error");
        assertEquals("Claude error", exception.getMessage());

        AgentSession.AgentException withCause = new AgentSession.AgentException("Claude error with cause", new RuntimeException("claude inner"));
        assertEquals("Claude error with cause", withCause.getMessage());
        assertNotNull(withCause.getCause());
    }

    @Test
    void testWorkerHostNullMeansLocal() {
        ClaudeSession session = new ClaudeSession(config, tempDir.toString(), null);
        // Constructor should not throw
        assertNotNull(session);
    }

    @Test
    void testWorkerHostWithValue() {
        ClaudeSession session = new ClaudeSession(config, tempDir.toString(), "remote-host");
        // Constructor should not throw
        assertNotNull(session);
    }

    @Test
    void testAgentTypeValues() {
        assertEquals("codex", AgentSession.Type.CODEX.getValue());
        assertEquals("claude", AgentSession.Type.CLAUDE.getValue());
        assertEquals("gemini", AgentSession.Type.GEMINI.getValue());
    }

    @Test
    void testAgentTypeFromString() {
        assertEquals(AgentSession.Type.CODEX, AgentSession.Type.fromString("codex"));
        assertEquals(AgentSession.Type.CLAUDE, AgentSession.Type.fromString("claude"));
        assertEquals(AgentSession.Type.GEMINI, AgentSession.Type.fromString("gemini"));
        assertEquals(AgentSession.Type.CODEX, AgentSession.Type.fromString("CODEX"));
        assertEquals(AgentSession.Type.CLAUDE, AgentSession.Type.fromString("Claude"));
    }

    @Test
    void testAgentTypeFromNull() {
        assertEquals(AgentSession.Type.CODEX, AgentSession.Type.fromString(null));
    }

    @Test
    void testAgentTypeFromUnknown() {
        assertEquals(AgentSession.Type.CODEX, AgentSession.Type.fromString("unknown"));
    }

    @Test
    void testTurnResultRecord() {
        AgentSession.TurnResult result = new AgentSession.TurnResult(
                true,
                null,
                "sess",
                "thread",
                "turn",
                100L,
                200L,
                300L
        );

        assertEquals("sess", result.sessionId());
        assertEquals("thread", result.threadId());
        assertEquals("turn", result.turnId());
    }
}
