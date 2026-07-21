package help.lixin.symphony.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import help.lixin.symphony.agent.AgentSession;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import help.lixin.symphony.orchestrator.RateLimits;
import help.lixin.symphony.tracker.LinearClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Codex App-Server session manager
 *
 * Implements AgentSession interface for Codex provider.
 * Communication via JSON-RPC 2.0 over stdio.
 *
 * Message flow:
 * 1. initialize - Initialize session
 * 2. thread/start - Create thread
 * 3. turn/start - Start turn
 * 4. Receive event stream (tool calls, approvals, etc)
 * 5. turn/completed - Turn completed
 */
public class CodexSession implements AgentSession {
    private static final Logger logger = LoggerFactory.getLogger(CodexSession.class);

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

    // Message ID counter
    private int nextMessageId = 1;

    // Session state
    private String threadId;
    private String turnId;
    private String sessionId;
    private boolean initialized = false;

    // Token tracking - use long for large token counts
    private long totalInputTokens = 0;
    private long totalOutputTokens = 0;
    private long totalTokens = 0;

    public CodexSession(AppConfig config, String workspace, String workerHost) {
        this.config = config;
        this.workspace = workspace;
        this.workerHost = workerHost;
        this.objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // Create DynamicTool with LinearClient
        LinearClient linearClient = new LinearClient(config.getTracker());
        this.dynamicTool = new DynamicTool(linearClient);
    }

    @Override
    public Type getType() {
        return Type.CODEX;
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
     * Start Codex session
     *
     * @param eventCallback event callback
     */
    @Override
    public void start(Consumer<AgentEvent> eventCallback) throws AgentException {
        this.eventCallback = eventCallback;

        logger.info("Starting Codex session: workspace={}, worker={}",
                workspace, workerHost != null ? workerHost : "local");

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
            throw new AgentException("Failed to start Codex session", e);
        }
    }

    /**
     * Start local process
     */
    private void startLocalProcess() throws IOException {
        String command = config.getCodex().getCommand();

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
     * Start remote process (not implemented)
     */
    private void startRemoteProcess() {
        throw new UnsupportedOperationException("Remote process start not implemented");
    }

    /**
     * Initialize session
     */
    private void initialize() throws IOException, AgentException, InterruptedException {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", true);

        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "symphony-orchestrator");
        clientInfo.put("title", "Symphony Orchestrator");
        clientInfo.put("version", "0.1.0");

        sendMessage("initialize", params);

        // Wait for response
        JsonNode response = waitForResponse(1);
        if (response == null || response.has("error")) {
            throw new AgentException("Initialization failed");
        }

        // Send initialized notification
        sendNotification("initialized", objectMapper.createObjectNode());

        initialized = true;
        logger.info("Codex initialization complete");
    }

    /**
     * Start thread
     */
    public void startThread() throws IOException, AgentException, InterruptedException {
        if (!initialized) {
            throw new AgentException("Session not initialized");
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.putPOJO("approvalPolicy", getApprovalPolicy());
        params.put("sandbox", config.getCodex().getThreadSandbox());
        params.put("cwd", workspace);
        params.putPOJO("dynamicTools", objectMapper.valueToTree(DynamicTool.toolSpecs()));

        sendMessage("thread/start", params);

        JsonNode response = waitForResponse(2);
        if (response == null || response.has("error")) {
            throw new AgentException("Failed to start thread");
        }

        threadId = response.path("result").path("thread").path("id").asText();
        
        // Set session ID to thread ID
        sessionId = threadId;
        
        // Emit session_started event
        ObjectNode eventParams = objectMapper.createObjectNode();
        eventParams.put("sessionId", sessionId);
        eventParams.put("threadId", threadId);
        emitEvent("session_started", eventParams);
        
        logger.info("Thread started: threadId={}, sessionId={}", threadId, sessionId);
    }

    /**
     * Run a single turn
     *
     * @param prompt input prompt
     * @param issue related issue
     * @return turn result
     */
    @Override
    public TurnResult runTurn(String prompt, Issue issue) throws AgentException {
        if (threadId == null) {
            try {
                startThread();
            } catch (Exception e) {
                throw new AgentException("Failed to start thread", e);
            }
        }

        // Build turn parameters
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);

        // input must be array, each element with type and text
        ArrayNode inputArray = params.putArray("input");
        ObjectNode input = inputArray.addObject();
        input.put("type", "text");
        input.put("text", prompt);

        params.put("cwd", workspace);
        params.put("title", issue.getIdentifier() + ": " + issue.getTitle());
        params.putPOJO("approvalPolicy", getApprovalPolicy());
        params.putPOJO("sandboxPolicy", getTurnSandboxPolicy());

        // Start turn
        try {
            sendMessage("turn/start", params);
            JsonNode response = waitForResponse(3);

            if (response == null || response.has("error")) {
                throw new AgentException("Failed to start turn");
            }

            turnId = response.path("result").path("turn").path("id").asText();
            logger.info("Turn started: turnId={}", turnId);

        } catch (IOException | InterruptedException e) {
            throw new AgentException("Failed to send turn request", e);
        }

        // Wait for turn completion
        return waitForTurnCompletion();
    }

    /**
     * Wait for turn completion
     */
    private TurnResult waitForTurnCompletion() throws AgentException {
        // Record turn start token baseline
        long turnStartInputTokens = totalInputTokens;
        long turnStartOutputTokens = totalOutputTokens;
        long turnStartTotalTokens = totalTokens;

        while (true) {
            try {
                JsonNode message = messageQueue.poll(5, TimeUnit.MINUTES);
                if (message == null) {
                    throw new AgentException("Message wait timeout");
                }

                String method = message.path("method").asText();
                JsonNode params = message.path("params");

                // Extract token usage from multiple paths
                extractTokenUsageFromMultiplePaths(params);
                
                // Extract rate limits
                RateLimits rateLimits = extractRateLimits(params);
                
                // Extract sessionId
                String msgSessionId = extractSessionId(params);
                if (msgSessionId != null && !msgSessionId.isBlank()) {
                    this.sessionId = msgSessionId;
                }

                // Create event and notify callback
                AgentEvent event = new AgentEvent(
                        method, 
                        params, 
                        sessionId,
                        totalInputTokens,
                        totalOutputTokens,
                        totalTokens,
                        rateLimits
                );
                eventCallback.accept(event);

                switch (method) {
                    case "turn/completed" -> {
                        // Calculate this turn's token usage
                        long turnInputTokens = totalInputTokens - turnStartInputTokens;
                        long turnOutputTokens = totalOutputTokens - turnStartOutputTokens;
                        long turnTotalTokens = totalTokens - turnStartTotalTokens;
                        
                        logger.info("Turn completed: inputTokens={}, outputTokens={}, totalTokens={}", 
                                turnInputTokens, turnOutputTokens, turnTotalTokens);
                        return new TurnResult(
                                true, null, sessionId, threadId, turnId,
                                totalInputTokens, totalOutputTokens, totalTokens);
                    }
                    case "turn/failed" -> {
                        return TurnResult.failure(params.path("error").asText("Unknown error"));
                    }
                    case "turn/cancelled" -> {
                        return TurnResult.failure("Cancelled");
                    }
                    case "item/tool/call" -> {
                        handleToolCall(message);
                    }
                    case "mcpServer/elicitation/request" -> {
                        logger.warn("Codex requested user elicitation — auto-responding to prevent timeout");
                        int msgId = message.path("id").asInt(-1);
                        if (msgId >= 0) {
                            try {
                                ObjectNode response = objectMapper.createObjectNode();
                                response.put("jsonrpc", "2.0");
                                response.put("id", msgId);
                                ObjectNode result = objectMapper.createObjectNode();
                                result.put("message", "Continue working autonomously. No user input available.");
                                response.set("result", result);
                                writer.write(objectMapper.writeValueAsString(response) + "\n");
                                writer.flush();
                                logger.info("Sent auto-response to elicitation id={}", msgId);
                            } catch (IOException e) {
                                logger.error("Failed to send elicitation auto-response: {}", e.getMessage());
                            }
                        }
                    }
                    case "session/started" -> {
                        String newSessionId = params.path("sessionId").asText(null);
                        if (newSessionId != null && !newSessionId.isBlank()) {
                            this.sessionId = newSessionId;
                            logger.info("Session ID updated: {}", newSessionId);
                        }
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException("Wait interrupted");
            }
        }
    }

    /**
     * Enhanced token extraction from multiple paths
     */
    private void extractTokenUsageFromMultiplePaths(JsonNode params) {
        // Path 1: params.msg.payload.info.total_token_usage
        extractTokenFromNode(params, "msg", "payload", "info", "total_token_usage");
        
        // Path 2: params.msg.payload.info.totalTokenUsage
        extractTokenFromNode(params, "msg", "payload", "info", "totalTokenUsage");
        
        // Path 3: params.msg.info.total_token_usage
        extractTokenFromNode(params, "msg", "info", "total_token_usage");
        
        // Path 4: params.msg.info.totalTokenUsage
        extractTokenFromNode(params, "msg", "info", "totalTokenUsage");
        
        // Path 5: params.tokenUsage.total
        extractTokenFromPath(params, "tokenUsage", "total");
        
        // Path 6: params.totalTokenUsage
        extractTokenFromPath(params, "totalTokenUsage");
        
        // Path 7: params.total_token_usage
        extractTokenFromPath(params, "total_token_usage");
        
        // Path 8: result.msg.payload.info.total_token_usage
        JsonNode result = params.path("result");
        extractTokenFromNode(result, "msg", "payload", "info", "total_token_usage");
        extractTokenFromNode(result, "msg", "payload", "info", "totalTokenUsage");
        extractTokenFromNode(result, "msg", "info", "total_token_usage");
        extractTokenFromNode(result, "msg", "info", "totalTokenUsage");
        extractTokenFromPath(result, "tokenUsage", "total");
        extractTokenFromPath(result, "totalTokenUsage");
        extractTokenFromPath(result, "total_token_usage");
        
        // Path 9: Check result.result (double nested)
        JsonNode innerResult = result.path("result");
        extractTokenFromPath(innerResult, "tokenUsage", "total");
        extractTokenFromPath(innerResult, "totalTokenUsage");
        extractTokenFromPath(innerResult, "total_token_usage");
        
        // Path 10: Check msg usage
        JsonNode msgNode = params.path("msg");
        if (msgNode.isObject()) {
            extractTokenUsageFromNode(msgNode);
        }
        
        // Path 11: Check result.msg usage
        JsonNode resultMsg = result.path("msg");
        if (resultMsg.isObject()) {
            extractTokenUsageFromNode(resultMsg);
        }
        
        // Path 12: Check params.msg usage
        JsonNode msgParams = params.path("msg");
        if (msgParams.isObject()) {
            JsonNode msgUsage = msgParams.path("usage");
            if (msgUsage.isObject()) {
                extractTokenUsageFromNode(msgUsage);
            }
        }
        
        // Path 13: Check params.msg.payload usage
        JsonNode msgPayload = params.path("msg").path("payload");
        if (msgPayload.isObject()) {
            JsonNode payloadUsage = msgPayload.path("usage");
            if (payloadUsage.isObject()) {
                extractTokenUsageFromNode(payloadUsage);
            }
        }
    }
    
    /**
     * Extract token from specified path
     */
    private void extractTokenFromPath(JsonNode node, String... path) {
        JsonNode target = node;
        for (String key : path) {
            if (target == null || target.isMissingNode()) return;
            target = target.path(key);
        }
        if (target != null && target.isObject()) {
            extractTokenUsageFromNode(target);
        }
    }
    
    /**
     * Extract token from specified path (with variable args)
     */
    private void extractTokenFromNode(JsonNode node, String... path) {
        JsonNode target = node;
        for (String key : path) {
            if (target == null || target.isMissingNode()) return;
            target = target.path(key);
        }
        if (target != null && target.isObject()) {
            extractTokenUsageFromNode(target);
        }
    }
    
    /**
     * Extract token usage from node
     */
    private void extractTokenUsageFromNode(JsonNode node) {
        if (node == null || !node.isObject()) return;
        
        // Check for nested usage
        JsonNode usage = node.path("usage");
        if (usage.isObject()) {
            extractTokenUsageFromNode(usage);
            return;
        }
        
        long foundInput = 0;
        long foundOutput = 0;
        long foundTotal = 0;
        boolean foundAny = false;
        
        // Input tokens
        JsonNode inputTokens = node.path("inputTokens");
        if (inputTokens.isMissingNode()) inputTokens = node.path("input_tokens");
        if (inputTokens.isMissingNode()) inputTokens = node.path("prompt_tokens");
        if (inputTokens.isNumber()) {
            foundInput = inputTokens.asLong();
            foundAny = true;
        }
        
        // Output tokens
        JsonNode outputTokens = node.path("outputTokens");
        if (outputTokens.isMissingNode()) outputTokens = node.path("output_tokens");
        if (outputTokens.isMissingNode()) outputTokens = node.path("completion_tokens");
        if (outputTokens.isNumber()) {
            foundOutput = outputTokens.asLong();
            foundAny = true;
        }
        
        // Total tokens
        JsonNode totalTokensNode = node.path("totalTokens");
        if (totalTokensNode.isMissingNode()) totalTokensNode = node.path("total_tokens");
        if (totalTokensNode.isMissingNode()) totalTokensNode = node.path("tokens");
        if (totalTokensNode.isNumber()) {
            foundTotal = totalTokensNode.asLong();
            foundAny = true;
        }
        
        // Estimate if only totalTokens found
        if (foundTotal > 0 && foundInput == 0 && foundOutput == 0) {
            foundInput = foundTotal * 2 / 3;
            foundOutput = foundTotal - foundInput;
        }
        
        // Use max for accumulation
        if (foundAny) {
            totalInputTokens = Math.max(totalInputTokens, foundInput);
            totalOutputTokens = Math.max(totalOutputTokens, foundOutput);
            totalTokens = Math.max(totalTokens, foundTotal);
            
            logger.debug("Token extracted: input={}, output={}, total={}", 
                    foundInput, foundOutput, foundTotal);
        }
    }

    /**
     * Extract sessionId from message
     */
    private String extractSessionId(JsonNode params) {
        String sessionId = params.path("sessionId").asText(null);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        
        sessionId = params.path("result").path("sessionId").asText(null);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        
        sessionId = params.path("msg").path("sessionId").asText(null);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        
        sessionId = params.path("threadId").asText(null);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        
        return null;
    }

    /**
     * Extract rate limits from message
     */
    private RateLimits extractRateLimits(JsonNode params) {
        JsonNode rateLimits = params.path("rateLimits");
        if (rateLimits.isMissingNode()) rateLimits = params.path("result").path("rateLimits");
        if (rateLimits.isMissingNode()) rateLimits = params.path("rate_limits");
        if (rateLimits.isMissingNode()) rateLimits = params.path("result").path("rate_limits");
        
        // Try from msg.payload
        if (rateLimits.isMissingNode()) {
            rateLimits = params.path("msg").path("payload").path("rateLimits");
        }
        if (rateLimits.isMissingNode()) {
            rateLimits = params.path("msg").path("payload").path("rate_limits");
        }
        
        if (rateLimits.isObject()) {
            try {
                String limitId = rateLimits.path("limitId").asText(null);
                RateLimits rl = RateLimits.fromJsonNode(rateLimits, limitId);
                if (rl != null) {
                    logger.debug("Rate limits extracted: {}", limitId);
                    return rl;
                }
            } catch (Exception e) {
                logger.debug("Parse rate limits failed: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Emit event to callback
     */
    private void emitEvent(String eventName, ObjectNode params) {
        AgentEvent event = new AgentEvent(
                eventName,
                params,
                sessionId,
                totalInputTokens,
                totalOutputTokens,
                totalTokens,
                null
        );
        if (eventCallback != null) {
            eventCallback.accept(event);
        }
    }

    /**
     * Handle tool call
     */
    private void handleToolCall(JsonNode message) throws AgentException {
        JsonNode params = message.path("params");
        String toolName = params.path("tool").asText();
        JsonNode arguments = params.path("arguments");
        int toolCallId = message.path("id").asInt();

        logger.info("Received tool call: tool={}, toolCallId={}", toolName, toolCallId);
        logger.debug("Tool call details: arguments={}", arguments);

        try {
            // Execute tool using DynamicTool
            Object args;
            if (arguments.isObject()) {
                args = objectMapper.convertValue(arguments, Map.class);
            } else if (arguments.isTextual()) {
                args = arguments.asText();
            } else {
                args = arguments.toString();
            }

            Map<String, Object> result = dynamicTool.execute(toolName, args);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("id", toolCallId);
            response.putPOJO("result", result);

            writer.write(objectMapper.writeValueAsString(response) + "\n");
            writer.flush();

            logger.info("Tool executed: tool={}, success={}", toolName, result.get("success"));

        } catch (Exception e) {
            logger.error("Tool execution failed: tool={}", toolName, e);
        }
    }

    /**
     * Send JSON-RPC message
     */
    private void sendMessage(String method, ObjectNode params) throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", nextMessageId++);
        message.put("method", method);
        message.set("params", params);

        writer.write(objectMapper.writeValueAsString(message) + "\n");
        writer.flush();

        logger.debug("Sent message: method={}, id={}", method, message.get("id"));
    }

    /**
     * Send JSON-RPC notification (no id)
     */
    private void sendNotification(String method, ObjectNode params) throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);

        writer.write(objectMapper.writeValueAsString(message) + "\n");
        writer.flush();

        logger.debug("Sent notification: method={}", method);
    }

    /**
     * Read messages loop
     */
    private void readMessages() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode message = objectMapper.readTree(line);
                    String method = message.path("method").asText(null);
                    logger.debug("Received message: method={}, id={}", method, message.path("id"));

                    // JSON-RPC routing:
                    // - has method + has id: request (needs handling)
                    // - has method + no id: notification (no response needed)
                    // - no method + has id: response (for waitForResponse)
                    boolean hasMethod = method != null && !method.isBlank();
                    boolean hasId = message.has("id") && !message.get("id").isNull();

                    if (hasMethod && hasId) {
                        // Request - add to event queue
                        messageQueue.put(message);
                    } else if (hasId) {
                        // Response - add to receivedResponses
                        receivedResponses.add(message);
                    } else {
                        // Notification - add to event queue
                        messageQueue.put(message);
                    }

                } catch (Exception e) {
                    logger.warn("Parse message failed: {}", line, e);
                }
            }
        } catch (Exception e) {
            logger.error("Read message stream failed", e);
        }
    }

    /**
     * Wait for response
     */
    private JsonNode waitForResponse(int expectedId) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeoutMs = config.getCodex().getReadTimeoutMs();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Find matching message and remove
            for (int i = 0; i < receivedResponses.size(); i++) {
                JsonNode message = receivedResponses.get(i);
                if (message.path("id").asInt() == expectedId) {
                    receivedResponses.remove(i);
                    return message;
                }
            }
            Thread.sleep(10);
        }

        return null;
    }

    private final CopyOnWriteArrayList<JsonNode> receivedResponses = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<JsonNode> messageQueue = new LinkedBlockingQueue<>();

    /**
     * Get approval policy
     */
    private Object getApprovalPolicy() {
        return config.getCodex().getApprovalPolicy();
    }

    /**
     * Get turn sandbox policy
     */
    private Map<String, Object> getTurnSandboxPolicy() {
        Object policy = config.getCodex().getTurnSandboxPolicy();
        if (policy instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapPolicy = (Map<String, Object>) policy;
            return mapPolicy;
        }

        // Default policy
        return Map.of(
                "type", "workspaceWrite",
                "writableRoots", java.util.List.of(workspace)
        );
    }

    /**
     * Stop session
     */
    @Override
    public void stop() {
        logger.info("Stopping Codex session");
        cleanup();
    }

    /**
     * Cleanup resources
     */
    /**
     * Get the process ID of the spawned codex process
     * @return process ID as string, or null if process not started
     */
    public String getProcessId() {
        if (process != null) {
            return String.valueOf(process.pid());
        }
        return null;
    }
    private void cleanup() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Codex event executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for Codex executor termination");
            }
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
}
