package help.lixin.symphony.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Gemini CLI session manager
 *
 * Implements AgentSession interface for Gemini provider.
 * Uses Gemini CLI with NDJSON streaming protocol.
 *
 * Gemini CLI command structure (from GeminiRunner.ts):
 * gemini --output-format stream-json --model gemini-2.5-pro
 *   --yolo --include-directories /path/to/worktree -p "<prompt>"
 *
 * Or streaming mode (stdin):
 * gemini --output-format stream-json --model gemini-2.5-pro
 *   --yolo --include-directories /path/to/worktree
 * stdin: {"type":"init","client":"cyrus-gemini-runner","version":"1.0.0"}
 * stdin: {"type":"message","message":"<prompt>"}
 *
 * Communication: NDJSON over stdin/stdout (Claude-compatible format)
 *
 * Environment variables:
 * - GEMINI_API_KEY: required
 * - GEMINI_SYSTEM_MD: optional system prompt file path
 */
public class GeminiSession implements AgentSession {
    private static final Logger logger = LoggerFactory.getLogger(GeminiSession.class);

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

    // Gemini config
    private String model = "gemini-2.5-pro";
    private boolean yolo = true; // Auto-approve
    private List<String> allowedDirectories = List.of();

    public GeminiSession(AppConfig config, String workspace, String workerHost) {
        this.config = config;
        this.workspace = workspace;
        this.workerHost = workerHost;
        this.objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // Create DynamicTool with LinearClient
        LinearClient linearClient = new LinearClient(config.getTracker());
        this.dynamicTool = new DynamicTool(linearClient);

        // Load Gemini-specific config
        loadGeminiConfig();
    }

    /**
     * Load Gemini-specific configuration
     */
    private void loadGeminiConfig() {
        if (config.getCodex() != null) {
            // Use model from codex config if specified
            if (config.getCodex().getModel() != null) {
                this.model = config.getCodex().getModel();
            }
            if (config.getCodex().getAllowedDirectories() != null) {
                this.allowedDirectories = config.getCodex().getAllowedDirectories();
            }
        }
    }

    @Override
    public Type getType() {
        return Type.GEMINI;
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
     * Start Gemini session
     */
    @Override
    public void start(Consumer<AgentEvent> eventCallback) throws AgentException {
        this.eventCallback = eventCallback;

        logger.info("Starting Gemini session: workspace={}, worker={}, model={}",
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
            throw new AgentException("Failed to start Gemini session", e);
        }
    }

    /**
     * Start local process
     */
    private void startLocalProcess() throws IOException {
        String command = buildGeminiCommand();

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
        pb.directory(new File(workspace));
        pb.environment().putAll(System.getenv());

        // Ensure GEMINI_API_KEY is set
        if (System.getenv("GEMINI_API_KEY") == null) {
            logger.warn("GEMINI_API_KEY not set in environment");
        }

        process = pb.start();

        reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Build Gemini CLI command
     *
     * Reference from GeminiRunner.ts:
     * gemini --output-format stream-json --model gemini-2.5-pro
     *   --yolo --include-directories /path/to/worktree -p "<prompt>"
     */
    private String buildGeminiCommand() {
        StringBuilder cmd = new StringBuilder();
        cmd.append("gemini");

        // Output format
        cmd.append(" --output-format stream-json");

        // Model
        cmd.append(" --model ").append(model);

        // Yolo mode (auto-approve)
        if (yolo) {
            cmd.append(" --yolo");
        }

        // Include directories
        if (allowedDirectories != null && !allowedDirectories.isEmpty()) {
            cmd.append(" --include-directories ").append(String.join(",", allowedDirectories));
        } else {
            cmd.append(" --include-directories ").append(workspace);
        }

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
        // Send init message for streaming mode
        ObjectNode initMsg = objectMapper.createObjectNode();
        initMsg.put("type", "init");
        initMsg.put("client", "symphony-orchestrator");
        initMsg.put("version", "0.1.0");

        writer.write(objectMapper.writeValueAsString(initMsg) + "\n");
        writer.flush();

        // Wait for init response
        JsonNode initResponse = waitForMessage(30, TimeUnit.SECONDS);
        if (initResponse == null) {
            throw new AgentException("Failed to receive initialization response from Gemini");
        }

        String type = initResponse.path("type").asText();
        if (!"init_ok".equals(type)) {
            logger.warn("Unexpected init response type: {}", type);
        }

        sessionId = extractSessionId(initResponse);
        initialized = true;
        logger.info("Gemini initialization complete, sessionId={}", sessionId);
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
            // Build prompt with issue context
            String fullPrompt = buildPrompt(prompt, issue);

            // Send message
            sendMessage(fullPrompt);

            // Wait for turn completion
            return waitForTurnCompletion(turnStartInputTokens, turnStartOutputTokens, turnStartTotalTokens);

        } catch (Exception e) {
            throw new AgentException("Failed to run turn", e);
        }
    }

    /**
     * Build prompt with issue context
     */
    private String buildPrompt(String prompt, Issue issue) {
        StringBuilder sb = new StringBuilder();

        if (issue != null) {
            sb.append("Working on Linear issue:\n");

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

            sb.append("\n");
        }

        sb.append(prompt);
        return sb.toString();
    }

    /**
     * Send message to Gemini
     */
    private void sendMessage(String message) throws IOException {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "message");
        msg.put("message", message);

        writer.write(objectMapper.writeValueAsString(msg) + "\n");
        writer.flush();

        logger.debug("Message sent to Gemini: {} chars", message.length());
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
                if (content.isMissingNode()) {
                    content = message.path("message");
                }

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
                    case "done", "complete" -> {
                        logger.info("Turn completed: tokens={}", totalTokens);
                        return new TurnResult(
                                true, null, sessionId, null, turnId,
                                totalInputTokens, totalOutputTokens, totalTokens);
                    }
                    case "error" -> {
                        String error = message.path("error").asText("Unknown error");
                        return TurnResult.failure(error);
                    }
                    case "tool_call", "function_call" -> {
                        handleToolCall(message);
                    }
                    case "interrupted", "cancelled" -> {
                        return TurnResult.failure(type);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException("Wait interrupted");
            }
        }
    }

    /**
     * Handle tool call from Gemini
     */
    private void handleToolCall(JsonNode message) throws AgentException {
        // Gemini may use different formats
        String toolName = message.path("name").asText(null);
        if (toolName == null) {
            toolName = message.path("tool").asText(null);
        }
        if (toolName == null) {
            toolName = message.path("function").asText(null);
        }

        JsonNode toolArgs = message.path("arguments");
        if (toolArgs.isMissingNode()) {
            toolArgs = message.path("args");
        }
        if (toolArgs.isMissingNode()) {
            toolArgs = message.path("input");
        }

        logger.info("Received tool call from Gemini: tool={}", toolName);

        try {
            Object args;
            if (toolArgs.isObject()) {
                args = objectMapper.convertValue(toolArgs, Map.class);
            } else if (toolArgs.isTextual()) {
                args = toolArgs.asText();
            } else {
                args = toolArgs.toString();
            }

            Map<String, Object> result = dynamicTool.execute(toolName, args);

            // Send result back
            ObjectNode resultMsg = objectMapper.createObjectNode();
            resultMsg.put("type", "tool_result");
            resultMsg.put("name", toolName);
            resultMsg.putPOJO("result", result.get("output"));

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
        if (usage.isMissingNode()) usage = message.path("tokenUsage");
        if (usage.isMissingNode()) usage = message.path("token_usage");
        if (usage.isMissingNode()) usage = message.path("candidates").path("usage");
        if (usage.isMissingNode()) {
            JsonNode promptFeedback = message.path("promptFeedback");
            if (!promptFeedback.isMissingNode()) {
                usage = promptFeedback.path("usage");
            }
        }

        if (usage.isObject()) {
            JsonNode inputTokens = usage.path("inputTokens");
            if (inputTokens.isMissingNode()) inputTokens = usage.path("input_tokens");
            if (inputTokens.isMissingNode()) inputTokens = usage.path("promptTokenCount");
            if (inputTokens.isNumber()) {
                totalInputTokens = Math.max(totalInputTokens, inputTokens.asLong());
            }

            JsonNode outputTokens = usage.path("outputTokens");
            if (outputTokens.isMissingNode()) outputTokens = usage.path("output_tokens");
            if (outputTokens.isMissingNode()) outputTokens = usage.path("candidatesTokenCount");
            if (outputTokens.isNumber()) {
                totalOutputTokens = Math.max(totalOutputTokens, outputTokens.asLong());
            }

            JsonNode total = usage.path("totalTokens");
            if (total.isMissingNode()) total = usage.path("total_tokens");
            if (total.isMissingNode()) total = usage.path("totalTokenCount");
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
        if (rateLimits.isMissingNode()) rateLimits = message.path("quota");

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
                    // Gemini uses NDJSON format (newline-delimited JSON)
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
        logger.info("Stopping Gemini session");
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
        return null; // Not implemented for Gemini
    }}
