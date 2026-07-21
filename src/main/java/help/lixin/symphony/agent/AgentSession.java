package help.lixin.symphony.agent;

import com.fasterxml.jackson.databind.JsonNode;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import help.lixin.symphony.orchestrator.RateLimits;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Unified Agent Session Interface
 *
 * Provides a provider-agnostic abstraction for AI agent sessions.
 * Implementations: CodexSession, ClaudeSession, GeminiSession
 *
 * Agent types:
 * - codex: JSON-RPC 2.0 over stdio
 * - claude: Claude CLI with SDK protocol
 * - gemini: Gemini CLI with NDJSON streaming
 */
public interface AgentSession {

    /**
     * Agent provider type
     */
    enum Type {
        CODEX("codex"),
        CLAUDE("claude"),
        GEMINI("gemini");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Type fromString(String type) {
            if (type == null) {
                return CODEX; // default
            }
            for (Type t : values()) {
                if (t.value.equalsIgnoreCase(type)) {
                    return t;
                }
            }
            return CODEX;
        }
    }

    /**
     * Agent event from session
     *
     * Events include:
     * - session_started: Session initialization complete
     * - turn_started: New turn begins
     * - turn_completed: Turn finished successfully
     * - turn_failed: Turn failed with error
     * - turn_cancelled: Turn was cancelled
     * - item/tool/call: Tool invocation request
     * - session/started: Provider-specific session event
     */
    record AgentEvent(
            String method,
            JsonNode params,
            String sessionId,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            RateLimits rateLimits,
            Instant timestamp
    ) {
        public AgentEvent(String method, JsonNode params, String sessionId,
                         long inputTokens, long outputTokens, long totalTokens, RateLimits rateLimits) {
            this(method, params, sessionId, inputTokens, outputTokens, totalTokens, rateLimits, Instant.now());
        }
    }

    /**
     * Turn execution result
     */
    record TurnResult(
            boolean completed,
            String error,
            String sessionId,
            String threadId,
            String turnId,
            long inputTokens,
            long outputTokens,
            long totalTokens
    ) {
        public static TurnResult success(String sessionId, long inputTokens, long outputTokens, long totalTokens) {
            return new TurnResult(true, null, sessionId, null, null, inputTokens, outputTokens, totalTokens);
        }

        public static TurnResult failure(String error) {
            return new TurnResult(false, error, null, null, null, 0, 0, 0);
        }

        public boolean isCompleted() {
            return completed;
        }

        public String getError() {
            return error;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getThreadId() {
            return threadId;
        }

        public String getTurnId() {
            return turnId;
        }

        public long getInputTokens() {
            return inputTokens;
        }

        public long getOutputTokens() {
            return outputTokens;
        }

        public long getTotalTokens() {
            return totalTokens;
        }
    }

    /**
     * Agent exception
     */
    class AgentException extends Exception {
        public AgentException(String message) {
            super(message);
        }

        public AgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Get the agent type
     */
    Type getType();

    /**
     * Get session ID
     */
    String getSessionId();

    /**
     * Get total input tokens
     */
    long getInputTokens();

    /**
     * Get total output tokens
     */
    long getOutputTokens();

    /**
     * Get total tokens
     */
    long getTotalTokens();

    /**
     * Start the agent session
     *
     * @param eventCallback callback for session events
     * @throws AgentException if start fails
     */
    void start(Consumer<AgentEvent> eventCallback) throws AgentException;

    /**
     * Run a single turn
     *
     * @param prompt input prompt
     * @param issue related issue
     * @return turn result
     * @throws AgentException if turn fails
     */
    TurnResult runTurn(String prompt, Issue issue) throws AgentException;

    /**
     * Stop the session
     */
    void stop();

    /**
     * Get the process ID of the spawned agent process
     * @return process ID as string, or null if process not started
     */
    String getProcessId();}
