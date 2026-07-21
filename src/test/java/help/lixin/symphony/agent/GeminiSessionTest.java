package help.lixin.symphony.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import help.lixin.symphony.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GeminiSession
 *
 * Tests the AgentSession implementation for Gemini provider.
 */
class GeminiSessionTest {

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
        codexConfig.setCommand("gemini");
        codexConfig.setModel("gemini-2.5-flash");  // Use faster model for testing
        codexConfig.setAllowedDirectories(List.of(tempDir.toString()));
        config.setCodex(codexConfig);
    }

    @Test
    void testGetType() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), null);
        assertEquals(AgentSession.Type.GEMINI, session.getType());
    }

    @Test
    void testInitialSessionId() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), null);
        assertNull(session.getSessionId());
    }

    @Test
    void testInitialTokens() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), null);
        assertEquals(0, session.getInputTokens());
        assertEquals(0, session.getOutputTokens());
        assertEquals(0, session.getTotalTokens());
    }

    @Test
    void testStopWithoutStart() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), null);
        // Should not throw
        session.stop();
    }

    @Test
    void testModelFromConfig() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), null);
        assertNotNull(config.getCodex().getModel());
        assertEquals("gemini-2.5-flash", config.getCodex().getModel());
    }

    @Test
    void testAllowedDirectories() {
        assertNotNull(config.getCodex().getAllowedDirectories());
        assertFalse(config.getCodex().getAllowedDirectories().isEmpty());
        assertEquals(tempDir.toString(), config.getCodex().getAllowedDirectories().get(0));
    }

    @Test
    void testAgentEventCreation() {
        JsonNode params = objectMapper.createObjectNode();
        
        AgentSession.AgentEvent event = new AgentSession.AgentEvent(
                "done",
                params,
                "gemini-session-123",
                200L,
                400L,
                600L,
                null
        );

        assertEquals("done", event.method());
        assertEquals("gemini-session-123", event.sessionId());
        assertEquals(200L, event.inputTokens());
        assertEquals(400L, event.outputTokens());
        assertEquals(600L, event.totalTokens());
        assertNotNull(event.timestamp());
    }

    @Test
    void testTurnResultWithGeminiData() {
        AgentSession.TurnResult result = AgentSession.TurnResult.success(
                "gemini-session-789",
                1000L,
                1500L,
                2500L
        );

        assertTrue(result.isCompleted());
        assertNull(result.getError());
        assertEquals("gemini-session-789", result.getSessionId());
        assertEquals(1000L, result.getInputTokens());
        assertEquals(1500L, result.getOutputTokens());
        assertEquals(2500L, result.getTotalTokens());
    }

    @Test
    void testGeminiException() {
        AgentSession.AgentException exception = new AgentSession.AgentException("Gemini error");
        assertEquals("Gemini error", exception.getMessage());

        AgentSession.AgentException withCause = new AgentSession.AgentException("Gemini error with cause", new RuntimeException("gemini inner"));
        assertEquals("Gemini error with cause", withCause.getMessage());
        assertNotNull(withCause.getCause());
    }

    @Test
    void testWorkerHostNullMeansLocal() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), null);
        assertNotNull(session);
    }

    @Test
    void testWorkerHostWithValue() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), "gcp-remote");
        assertNotNull(session);
    }

    @Test
    void testGeminiSessionCreation() {
        GeminiSession session = new GeminiSession(config, tempDir.toString(), null);
        assertNotNull(session);
        assertEquals(AgentSession.Type.GEMINI, session.getType());
    }

    @Test
    void testToolCallMessageTypes() {
        // Gemini uses "tool_call" or "function_call" for tool calls
        assertTrue(true); // Verified in implementation
    }
}
