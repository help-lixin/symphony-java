package help.lixin.symphony.agent;

import help.lixin.symphony.codex.CodexSession;
import help.lixin.symphony.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating AgentSession instances
 *
 * Supports dynamic switching between:
 * - Codex: JSON-RPC 2.0 over stdio
 * - Claude: Claude CLI with native protocol
 * - Gemini: Gemini CLI with NDJSON streaming
 *
 * Usage:
 * AgentSession session = AgentSessionFactory.create(config, workspace, workerHost);
 * session.start(event -> handleEvent(event));
 * TurnResult result = session.runTurn(prompt, issue);
 */
public class AgentSessionFactory {

    private static final Logger logger = LoggerFactory.getLogger(AgentSessionFactory.class);

    private AgentSessionFactory() {
        // utility class
    }

    /**
     * Create an AgentSession based on configuration
     *
     * @param config application config
     * @param workspace working directory
     * @param workerHost SSH host (null for local)
     * @return appropriate session instance
     */
    public static AgentSession create(AppConfig config, String workspace, String workerHost) {
        AgentSession.Type type = determineAgentType(config);

        logger.info("Creating agent session: type={}, workspace={}, worker={}",
                type, workspace, workerHost != null ? workerHost : "local");

        return switch (type) {
            case CLAUDE -> new ClaudeSession(config, workspace, workerHost);
            case GEMINI -> new GeminiSession(config, workspace, workerHost);
            case CODEX -> new CodexSession(config, workspace, workerHost);
        };
    }

    /**
     * Determine agent type from configuration
     *
     * Priority:
     * 1. codex.kind in config
     * 2. codex.command starts with known agent prefix
     * 3. Default to CODEX
     */
    private static AgentSession.Type determineAgentType(AppConfig config) {
        // Check for explicit kind setting
        if (config.getCodex() != null && config.getCodex().getKind() != null) {
            return AgentSession.Type.fromString(config.getCodex().getKind());
        }

        // Infer from command if available
        if (config.getCodex() != null && config.getCodex().getCommand() != null) {
            String command = config.getCodex().getCommand().toLowerCase();
            if (command.contains("claude")) {
                return AgentSession.Type.CLAUDE;
            }
            if (command.contains("gemini")) {
                return AgentSession.Type.GEMINI;
            }
        }

        // Default to codex
        return AgentSession.Type.CODEX;
    }

    /**
     * Create by explicit type
     *
     * @param type agent type
     * @param config application config
     * @param workspace working directory
     * @param workerHost SSH host (null for local)
     * @return session instance
     */
    public static AgentSession create(AgentSession.Type type, AppConfig config, String workspace, String workerHost) {
        logger.info("Creating agent session: type={}, workspace={}, worker={}",
                type, workspace, workerHost != null ? workerHost : "local");

        return switch (type) {
            case CLAUDE -> new ClaudeSession(config, workspace, workerHost);
            case GEMINI -> new GeminiSession(config, workspace, workerHost);
            case CODEX -> new CodexSession(config, workspace, workerHost);
        };
    }
}
