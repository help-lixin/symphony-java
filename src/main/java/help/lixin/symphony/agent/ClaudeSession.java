package help.lixin.symphony.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import help.lixin.symphony.codex.DynamicTool;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import help.lixin.symphony.orchestrator.RateLimits;
import help.lixin.symphony.tracker.LinearClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Claude CLI session manager
 *
 * Implements AgentSession interface for Claude provider.
 * Uses Claude CLI with SDK protocol for communication.
 *
 * Claude CLI command structure (from ClaudeRunner.ts):
 * claude -p "<system_prompt>" --model opus --output-format stream-json
 *   --add-dir /path/to/worktree --allowed-tools Read,Write,Bash
 *   --mcp-config /path/to/mcp.json < prompt_content
 *
 * Communication: stream-json over stdout
 */
public class ClaudeSession implements AgentSession {
    private static final Logger logger = LoggerFactory.getLogger(ClaudeSession.class);

    private final AppConfig config;
    private final String workspace;
    private final String workerHost;
    private final ObjectMapper objectMapper;
    private final DynamicTool dynamicTool;

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private ExecutorService executor;
    private Consumer<AgentEvent> eventCallback;

    // Session state
    private String sessionId;
    private String turnId;
    private boolean initialized = false;

    // Token tracking
    private long totalInputTokens = 0;
    private long totalOutputTokens = 0;
    private long totalTokens = 0;

    // Claude config
    private String model = "opus";
    private String fallbackModel = "sonnet";
    private List<String> allowedTools = List.of("Read", "Write", "Bash", "Grep", "Glob");
    private List<String> disallowedTools = List.of();
    private int maxTurns = 20;

    public ClaudeSession(AppConfig config, String workspace, String workerHost) {
        this.config = config;
        this.workspace = workspace;
        this.workerHost = workerHost;
        this.objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // Create DynamicTool with LinearClient
        LinearClient linearClient = new LinearClient(config.getTracker());
        this.dynamicTool = new DynamicTool(linearClient);

        // Load Claude-specific config
        loadClaudeConfig();
    }

    /**
     * Load Claude-specific configuration
     */
    private void loadClaudeConfig() {
        if (config.getCodex() != null) {
            // Use model from codex config if specified
            if (config.getCodex().getModel() != null) {
                this.model = config.getCodex().getModel();
            }
            if (config.getCodex().getMaxTurns() > 0) {
                this.maxTurns = config.getCodex().getMaxTurns();
            }
        }
    }

    @Override
    public Type getType() {
        return Type.CLAUDE;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public long getInputTokens() {
        return totalInputTokens;
    }

    @Override
    public long getOutputTokens() {
        return totalOutputTokens;
    }

    @Override
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * Start Claude session
     */
    @Override
    public void start(Consumer<AgentEvent> eventCallback) throws AgentException {
        this.eventCallback = eventCallback;

        logger.info("Starting Claude session: workspace={}, worker={}, model={}",
                workspace, workerHost != null ? workerHost : "local", model);

        try {
            if (workerHost == null) {
                startLocalProcess();
            } else {
                startRemoteProcess();
            }

            // Start message reading thread
            this.executor = Executors.newSingleThreadExecutor();
            this.executor.submit(this::readMessages);

            // Initialize session
            initialize();

        } catch (Exception e) {
            cleanup();
            throw new AgentException("Failed to start Claude session", e);
        }
    }

    /**
     * Start local process
     */
    private void startLocalProcess() throws IOException {
        String command = buildClaudeCommand();

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
        pb.directory(new File(workspace));
        pb.environment().putAll(System.getenv());

        process = pb.start();

        reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Build Claude CLI command
     *
     * Reference from ClaudeRunner.ts:
     * claude -p "<system_prompt>" --model opus --output-format stream-json
     *   --add-dir /path/to/worktree --allowed-tools Read,Write,Bash
     *   --mcp-config /path/to/mcp.json < prompt_content
     */
    private String buildClaudeCommand() {
        StringBuilder cmd = new StringBuilder();
        cmd.append("claude");

        // Model
        cmd.append(" --model ").append(model);
        if (fallbackModel != null && !fallbackModel.isBlank()) {
            cmd.append(" --fallback-model ").append(fallbackModel);
        }

        // Output format
        cmd.append(" --output-format stream-json");

        // Add working directory
        cmd.append(" --add-dir ").append(workspace);

        // Allowed tools
        if (allowedTools != null && !allowedTools.isEmpty()) {
            cmd.append(" --allowed-tools ").append(String.join(",", allowedTools));
        }

        // Disallowed tools
        if (disallowedTools != null && !disallowedTools.isEmpty()) {
            cmd.append(" --disallowed-tools ").append(String.join(",", disallowedTools));
        }

        // Max turns
        cmd.append(" --max-turns ").append(maxTurns);

        // No settings
        cmd.append(" --no-settings");

        return cmd.toString();
    }

    /**
     * Start remote process (not implemented)
     */
    private void startRemoteProcess() {
        throw new UnsupportedOperationException("Remote process start not implemented");
    }

    /**
     * Initialize session
     */
    private void initialize() throws IOException, AgentException, InterruptedException {
        // Claude CLI initializes automatically on start
        // Wait for initial message
        JsonNode initMessage = waitForMessage(30, TimeUnit.SECONDS);
        if (initMessage == null) {
            throw new AgentException("Failed to receive initialization message from Claude");
        }

        sessionId = extractSessionId(initMessage);
        initialized = true;
        logger.info("Claude initialization complete, sessionId={}", sessionId);
    }

    /**
     * Run a single turn
     */
    @Override
    public TurnResult runTurn(String prompt, Issue issue) throws AgentException {
        if (!initialized) {
            throw new AgentException("Session not initialized");
        }

        // Record turn start token baseline
        long turnStartInputTokens = totalInputTokens;
        long turnStartOutputTokens = totalOutputTokens;
        long turnStartTotalTokens = totalTokens;

        try {
            // Send prompt to Claude
            sendPrompt(prompt, issue);

            // Wait for turn completion
            return waitForTurnCompletion(turnStartInputTokens, turnStartOutputTokens, turnStartTotalTokens);

        } catch (Exception e) {
            throw new AgentException("Failed to run turn", e);
        }
    }

    /**
     * Send prompt to Claude
     */
    private void sendPrompt(String prompt, Issue issue) throws IOException {
        // Build system prompt with issue context
        String systemPrompt = buildSystemPrompt(issue);

        // Send system prompt
        ObjectNode sysMsg = objectMapper.createObjectNode();
        sysMsg.put("type", "system");
        sysMsg.put("text", systemPrompt);
        writer.write(objectMapper.writeValueAsString(sysMsg) + "\n");

        // Send user prompt
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("type", "user");
        userMsg.put("text", prompt);
        writer.write(objectMapper.writeValueAsString(userMsg) + "\n");

        writer.flush();
        logger.debug("Prompt sent to Claude");
    }

    /**
     * Build system prompt with issue context
     */
    private String buildSystemPrompt(Issue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are working on a Linear issue.\n\n");

        if (issue != null) {
            if (issue.getIdentifier() != null) {
                sb.append("Identifier: ").append(issue.getIdentifier()).append("\n");
            }
            if (issue.getTitle() != null) {
                sb.append("Title: ").append(issue.getTitle()).append("\n");
            }
            if (issue.getDescription() != null) {
                sb.append("Description:\n").append(issue.getDescription()).append("\n");
            }
            if (issue.getState() != null) {
                sb.append("State: ").append(issue.getState()).append("\n");
            }
        }

        sb.append("\nUse the Linear API tools to interact with the issue tracker.");
        return sb.toString();
    }

    /**
     * Wait for turn completion
     */
    private TurnResult waitForTurnCompletion(long turnStartInput, long turnStartOutput, long turnStartTotal) throws AgentException {
        while (true) {
            try {
                JsonNode message = messageQueue.poll(5, TimeUnit.MINUTES);
                if (message == null) {
                    throw new AgentException("Message wait timeout");
                }

                String type = message.path("type").asText();
                JsonNode content = message.path("content");
                RateLimits rateLimits = extractRateLimits(message);

                // Extract session ID
                String msgSessionId = extractSessionId(message);
                if (msgSessionId != null && !msgSessionId.isBlank()) {
                    this.sessionId = msgSessionId;
                }

                // Extract token usage
                extractTokenUsage(message);

                // Emit event
                AgentEvent event = new AgentEvent(
                        type,
                        content,
                        sessionId,
                        totalInputTokens,
                        totalOutputTokens,
                        totalTokens,
                        rateLimits
                );
                eventCallback.accept(event);

                // Handle different message types
                switch (type) {
                    case "result", "complete" -> {
                        logger.info("Turn completed: tokens={}", totalTokens);
                        return new TurnResult(
                                true, null, sessionId, null, turnId,
                                totalInputTokens, totalOutputTokens, totalTokens);
                    }
                    case "error" -> {
                        String error = message.path("error").asText("Unknown error");
                        return TurnResult.failure(error);
                    }
                    case "tool_use" -> {
                        handleToolCall(message);
                    }
                    case "interrupted" -> {
                        return TurnResult.failure("Interrupted");
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException("Wait interrupted");
            }
        }
    }

    /**
     * Handle tool call from Claude
     */
    private void handleToolCall(JsonNode message) throws AgentException {
        String toolName = message.path("tool").asText();
        JsonNode toolInput = message.path("input");

        logger.info("Received tool call from Claude: tool={}", toolName);

        try {
            Object args;
            if (toolInput.isObject()) {
                args = objectMapper.convertValue(toolInput, Map.class);
            } else if (toolInput.isTextual()) {
                args = toolInput.asText();
            } else {
                args = toolInput.toString();
            }

            Map<String, Object> result = dynamicTool.execute(toolName, args);

            // Send result back
            ObjectNode resultMsg = objectMapper.createObjectNode();
            resultMsg.put("type", "tool_result");
            resultMsg.put("tool", toolName);
            resultMsg.putPOJO("content", result.get("output"));

            writer.write(objectMapper.writeValueAsString(resultMsg) + "\n");
            writer.flush();

            logger.info("Tool executed: tool={}, success={}", toolName, result.get("success"));

        } catch (Exception e) {
            logger.error("Tool execution failed: tool={}", toolName, e);
        }
    }

    /**
     * Extract token usage from message
     */
    private void extractTokenUsage(JsonNode message) {
        // Check usage in various paths
        JsonNode usage = message.path("usage");
        if (usage.isMissingNode()) usage = message.path("result").path("usage");
        if (usage.isMissingNode()) usage = message.path("tokenUsage");

        if (usage.isObject()) {
            JsonNode inputTokens = usage.path("inputTokens");
            if (inputTokens.isMissingNode()) inputTokens = usage.path("input_tokens");
            if (inputTokens.isMissingNode()) inputTokens = usage.path("prompt_tokens");
            if (inputTokens.isNumber()) {
                totalInputTokens = Math.max(totalInputTokens, inputTokens.asLong());
            }

            JsonNode outputTokens = usage.path("outputTokens");
            if (outputTokens.isMissingNode()) outputTokens = usage.path("output_tokens");
            if (outputTokens.isMissingNode()) outputTokens = usage.path("completion_tokens");
            if (outputTokens.isNumber()) {
                totalOutputTokens = Math.max(totalOutputTokens, outputTokens.asLong());
            }

            JsonNode total = usage.path("totalTokens");
            if (total.isMissingNode()) total = usage.path("total_tokens");
            if (total.isNumber()) {
                totalTokens = Math.max(totalTokens, total.asLong());
            }

            logger.debug("Tokens extracted: input={}, output={}, total={}",
                    totalInputTokens, totalOutputTokens, totalTokens);
        }
    }

    /**
     * Extract session ID from message
     */
    private String extractSessionId(JsonNode message) {
        String sessionId = message.path("sessionId").asText(null);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;

        sessionId = message.path("session_id").asText(null);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;

        return null;
    }

    /**
     * Extract rate limits from message
     */
    private RateLimits extractRateLimits(JsonNode message) {
        JsonNode rateLimits = message.path("rateLimits");
        if (rateLimits.isMissingNode()) rateLimits = message.path("rate_limits");

        if (rateLimits.isObject()) {
            try {
                String limitId = rateLimits.path("limitId").asText(null);
                RateLimits rl = RateLimits.fromJsonNode(rateLimits, limitId);
                if (rl != null) {
                    return rl;
                }
            } catch (Exception e) {
                logger.debug("Parse rate limits failed: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Read messages loop
     */
    private void readMessages() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    // Claude uses stream-json format, each line is a JSON object
                    JsonNode message = objectMapper.readTree(line);
                    logger.debug("Received message: type={}", message.path("type"));
                    messageQueue.put(message);
                } catch (Exception e) {
                    logger.warn("Parse message failed: {}", line, e);
                }
            }
        } catch (Exception e) {
            logger.error("Read message stream failed", e);
        }
    }

    /**
     * Wait for specific message
     */
    private JsonNode waitForMessage(long timeout, TimeUnit unit) throws InterruptedException {
        return messageQueue.poll(timeout, unit);
    }

    private final LinkedBlockingQueue<JsonNode> messageQueue = new LinkedBlockingQueue<>();

    /**
     * Stop session
     */
    @Override
    public void stop() {
        logger.info("Stopping Claude session");
        cleanup();
    }

    /**
     * Cleanup resources
     */
    private void cleanup() {
        if (executor != null) {
            executor.shutdownNow();
        }

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }

        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
        } catch (IOException e) {
            logger.warn("Close streams failed", e);
        }
    }

    @Override
    public String getProcessId() {
        return null; // Not implemented for Claude
    }}
