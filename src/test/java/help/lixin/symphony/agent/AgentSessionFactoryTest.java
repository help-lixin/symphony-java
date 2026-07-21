package help.lixin.symphony.agent;

import help.lixin.symphony.codex.CodexSession;
import help.lixin.symphony.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AgentSessionFactory
 *
 * Tests dynamic agent switching between codex, claude, and gemini.
 */
class AgentSessionFactoryTest {

    @TempDir
    Path tempDir;

    private AppConfig config;

    @BeforeEach
    void setUp() {
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
        codexConfig.setCommand("codex app-server");
        codexConfig.setReadTimeoutMs(1000);
        config.setCodex(codexConfig);
    }

    @Test
    void testCreateCodexSession() {
        config.getCodex().setKind("codex");
        config.getCodex().setCommand("codex app-server");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.CODEX, session.getType());
        assertTrue(session instanceof CodexSession);
    }

    @Test
    void testCreateClaudeSession() {
        config.getCodex().setKind("claude");
        config.getCodex().setCommand("claude");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.CLAUDE, session.getType());
        assertTrue(session instanceof ClaudeSession);
    }

    @Test
    void testCreateGeminiSession() {
        config.getCodex().setKind("gemini");
        config.getCodex().setCommand("gemini");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.GEMINI, session.getType());
        assertTrue(session instanceof GeminiSession);
    }

    @Test
    void testCreateByExplicitType_Codex() {
        AgentSession session = AgentSessionFactory.create(
                AgentSession.Type.CODEX, config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.CODEX, session.getType());
    }

    @Test
    void testCreateByExplicitType_Claude() {
        AgentSession session = AgentSessionFactory.create(
                AgentSession.Type.CLAUDE, config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.CLAUDE, session.getType());
    }

    @Test
    void testCreateByExplicitType_Gemini() {
        AgentSession session = AgentSessionFactory.create(
                AgentSession.Type.GEMINI, config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.GEMINI, session.getType());
    }

    @Test
    void testInferTypeFromCommand_Caude() {
        config.getCodex().setKind(null);
        config.getCodex().setCommand("claude -p \"test\"");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.CLAUDE, session.getType());
    }

    @Test
    void testInferTypeFromCommand_Gemini() {
        config.getCodex().setKind(null);
        config.getCodex().setCommand("gemini --output-format stream-json");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.GEMINI, session.getType());
    }

    @Test
    void testDefaultToCodex() {
        // No kind set, command doesn't match claude or gemini
        config.getCodex().setKind(null);
        config.getCodex().setCommand("some-unknown-command");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.CODEX, session.getType());
    }

    @Test
    void testCaseInsensitiveKind() {
        config.getCodex().setKind("CLAUDE");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);

        assertNotNull(session);
        assertEquals(AgentSession.Type.CLAUDE, session.getType());
    }

    @Test
    void testWorkerHostPassThrough() {
        config.getCodex().setKind("claude");

        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), "remote-host");

        assertNotNull(session);
        // Construction should not fail even with worker host
    }

    @Test
    void testAllSessionsImplementAgentSession() {
        // Verify all session types implement the interface
        // Verify AgentSession interface exists and is implemented
        assertNotNull(AgentSession.class);
        
        // The interface is implemented by concrete classes
        assertTrue(AgentSession.class.isAssignableFrom(CodexSession.class));
        assertTrue(AgentSession.class.isAssignableFrom(ClaudeSession.class));
        assertTrue(AgentSession.class.isAssignableFrom(GeminiSession.class));
    }

    @Test
    void testSessionCreationWithNullWorkerHost() {
        config.getCodex().setKind("gemini");
        AgentSession session = AgentSessionFactory.create(config, tempDir.toString(), null);
        assertNotNull(session);

        config.getCodex().setKind("claude");
        session = AgentSessionFactory.create(config, tempDir.toString(), null);
        assertNotNull(session);
    }
}
