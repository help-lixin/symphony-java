package help.lixin.symphony.agent;

import akka.actor.ActorRef;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.config.WorkerConfig;
import help.lixin.symphony.model.Issue;
import help.lixin.symphony.orchestrator.OrchestratorProtocol;
import help.lixin.symphony.tracker.Tracker;
import help.lixin.symphony.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Agent executor
 *
 * Runs a single Issue processing in an independent thread.
 * Uses AgentSessionFactory to create the appropriate agent session
 * based on configuration (codex, claude, or gemini).
 *
 * Execution flow:
 * 1. Select worker host (local or remote)
 * 2. Create workspace
 * 3. Start agent session and get process ID
 * 4. Send runtime info with process ID to orchestrator
 * 5. Run before_run hook
 * 6. Execute agent turns
 * 7. Run after_run hook
 * 8. Cleanup workspace
 *
 * Turn loop:
 * - Until Issue enters inactive state
 * - Or reaches max_turns limit
 * - Or requires human input
 */
public class AgentRunner {
    private static final Logger logger = LoggerFactory.getLogger(AgentRunner.class);

    private final AppConfig config;
    private final Tracker tracker;
    private final WorkspaceManager workspaceManager;
    private final ActorRef orchestratorRef;

    public AgentRunner(AppConfig config, Tracker tracker,
                       WorkspaceManager workspaceManager, ActorRef orchestratorRef) {
        this.config = config;
        this.tracker = tracker;
        this.workspaceManager = workspaceManager;
        this.orchestratorRef = orchestratorRef;
    }

    /**
     * Run agent
     *
     * @param issue Issue to process
     * @param workerHost SSH worker host (null for local)
     * @return completion stage
     */
    public CompletionStage<Void> run(Issue issue, String workerHost) {
        return CompletableFuture.runAsync(() -> {
            logger.info("Agent run started: issue={}, worker={}",
                    issue.getIdentifier(), workerHost != null ? workerHost : "local");

            AgentSession session = null;
            try {
                // Select worker host
                String selectedHost = selectWorkerHost(workerHost);

                // Create workspace
                Path workspace = workspaceManager.createForIssue(issue, selectedHost);

                // Create session first to get process ID
                session = AgentSessionFactory.create(config, workspace.toString(), selectedHost);
                
                // Start session (spawns the process)
                session.start(event -> handleAgentEvent(issue, event));
                
                // Get process ID after starting session
                String processId = session.getProcessId();
                
                // Send workspace info with process ID to orchestrator
                sendWorkerRuntimeInfo(issue, selectedHost, workspace, processId);

                // Run before_run hook
                workspaceManager.runHook(
                        config.getHooks().getBeforeRun(),
                        workspace, issue, "before_run", selectedHost);

                // Execute agent turns
                runAgentTurns(session, workspace, issue);

                // Run after_run hook
                workspaceManager.runHook(
                        config.getHooks().getAfterRun(),
                        workspace, issue, "after_run", selectedHost);

                logger.info("Agent run completed: issue={}", issue.getIdentifier());

            } catch (Exception e) {
                logger.error("Agent run failed: issue={}", issue.getIdentifier(), e);
                throw new RuntimeException(e);
            } finally {
                if (session != null) {
                    session.stop();
                }
            }
        });
    }

    /**
     * Select worker host
     */
    private String selectWorkerHost(String preferredHost) {
        if (config.getWorker() == null) {
            return null;
        }

        var hosts = config.getWorker().getSshHosts();

        if (hosts == null || hosts.isEmpty()) {
            return null;
        }

        if (preferredHost != null && !preferredHost.isBlank()) {
            return preferredHost;
        }

        return hosts.get(0);
    }
    
    /**
     * Execute agent turns loop
     */
    private void runAgentTurns(AgentSession session, Path workspace, Issue issue) {
        int maxTurns = config.getAgent().getMaxTurns();

        try {
            String prompt = buildPrompt(issue);

            // Execute turns
            for (int turnNumber = 1; turnNumber <= maxTurns; turnNumber++) {
                logger.info("Executing turn: issue={}, turn={}/{}",
                        issue.getIdentifier(), turnNumber, maxTurns);

                AgentSession.TurnResult result = session.runTurn(prompt, issue);

                if (!result.isCompleted()) {
                    logger.warn("Turn not completed: issue={}, turn={}, error={}",
                            issue.getIdentifier(), turnNumber, result.getError());
                    break;
                }

                // Check if should continue
                if (!shouldContinue(issue)) {
                    logger.info("Issue no longer needs processing: issue={}", issue.getIdentifier());
                    break;
                }

                // Next turn with continuation prompt
                prompt = buildContinuationPrompt(turnNumber, maxTurns);
            }

        } catch (Exception e) {
            logger.error("Agent session execution failed: issue={}", issue.getIdentifier(), e);
        }
    }

    /**
     * Handle agent event
     */
    private void handleAgentEvent(Issue issue, AgentSession.AgentEvent event) {
        logger.debug("Agent event: issue={}, method={}, sessionId={}, tokens={}",
                issue.getIdentifier(), event.method(), event.sessionId(), event.totalTokens());

        // Send event to orchestrator with token info
        if (orchestratorRef != null) {
            orchestratorRef.tell(
                    new OrchestratorProtocol.CodexWorkerUpdate(
                            issue.getId(),
                            event.timestamp(),
                            event.method(),
                            null, // payload - not using structured payload
                            event.sessionId(),
                            event.inputTokens(),
                            event.outputTokens(),
                            event.totalTokens(),
                            event.rateLimits()
                    ),
                    ActorRef.noSender()
            );
        }
    }

    /**
     * Build initial prompt
     */
    private String buildPrompt(Issue issue) {
        String template = config.getCodex() != null ? config.getCodex().getPromptTemplate() : null;

        if (template == null || template.isBlank()) {
            template = getDefaultPromptTemplate();
        }

        // Replace all issue fields
        String result = template
                .replace("{{ issue.identifier }}", issue.getIdentifier() != null ? issue.getIdentifier() : "")
                .replace("{{ issue.title }}", issue.getTitle() != null ? issue.getTitle() : "")
                .replace("{{ issue.description }}", issue.getDescription() != null ? issue.getDescription() : "No description provided.")
                .replace("{{ issue.state }}", issue.getState() != null ? issue.getState() : "")
                .replace("{{ issue.labels }}", issue.getLabels() != null ? String.join(", ", issue.getLabels()) : "")
                .replace("{{ issue.url }}", issue.getUrl() != null ? issue.getUrl() : "")
                .replace("{{ issue.priority }}", issue.getPriority() != null ? String.valueOf(issue.getPriority()) : "")
                .replace("{{ issue.branchName }}", issue.getBranchName() != null ? issue.getBranchName() : "");

        // Handle Liquid condition tags
        result = result.replaceAll("(?s)\\{%\\s*if\\s+attempt\\s*%\\}.*?\\{%\\s*endif\\s*%\\}", "");

        return result;
    }

    /**
     * Build continuation prompt
     */
    private String buildContinuationPrompt(int turnNumber, int maxTurns) {
        return String.format("""
                Continuation guidance:

                - The previous agent turn completed normally, but the Linear issue is still in an active state.
                - This is continuation turn #%d of %d for the current agent run.
                - Resume from the current workspace and workpad state instead of restarting from scratch.
                - The original task instructions and prior turn context are already present in this thread.
                - Focus on the remaining ticket work and do not end the turn while the issue stays active.
                """, turnNumber, maxTurns);
    }

    /**
     * Default prompt template
     */
    private String getDefaultPromptTemplate() {
        return """
                You are working on a Linear issue.

                Identifier: {{ issue.identifier }}
                Title: {{ issue.title }}

                Body:
                {{ issue.description }}
                """;
    }

    /**
     * Check if should continue processing
     */
    private boolean shouldContinue(Issue issue) {
        try {
            var states = tracker.fetchIssueStatesByIds(java.util.List.of(issue.getId())).toCompletableFuture().join();

            if (states.isEmpty()) {
                return false;
            }

            Issue latest = states.get(0);
            String state = latest.getState();

            boolean isActive = config.getTracker().getActiveStates().stream()
                    .map(String::toLowerCase)
                    .anyMatch(s -> s.equalsIgnoreCase(state.trim()));

            return isActive;

        } catch (Exception e) {
            logger.warn("Check issue state failed: issue={}", issue.getIdentifier(), e);
            return false;
        }
    }

    /**
     * Send workspace runtime info to orchestrator
     */
    private void sendWorkerRuntimeInfo(Issue issue, String workerHost, Path workspace, String codexProcessId) {
        if (orchestratorRef != null) {
            orchestratorRef.tell(
                    new OrchestratorProtocol.WorkerRuntimeInfo(
                            issue.getId(),
                            workerHost,
                            workspace.toString(),
                            codexProcessId
                    ),
                    ActorRef.noSender()
            );
        }
    }

    /**
     * Get worker config
     */
    private WorkerConfig getWorker() {
        return config.getWorker();
    }

    /**
     * Get prompt template
     */
    private String getPromptTemplate() {
        return null; // TODO: implement
    }
}
