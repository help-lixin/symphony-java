package help.lixin.symphony.agent;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import help.lixin.symphony.orchestrator.Orchestrator;
import help.lixin.symphony.tracker.Tracker;
import help.lixin.symphony.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Agent executor (Akka Typed)
 */
public class AgentRunnerTyped extends AbstractBehavior<AgentRunnerTyped.Command> {

    private static final Logger logger = LoggerFactory.getLogger(AgentRunnerTyped.class);

    private final AppConfig config;
    private final Tracker tracker;
    private final WorkspaceManager workspaceManager;
    private final ActorRef<Orchestrator.Command> orchestratorRef;

    public AgentRunnerTyped(ActorContext<Command> context,
                           AppConfig config,
                           Tracker tracker,
                           WorkspaceManager workspaceManager,
                           ActorRef<Orchestrator.Command> orchestratorRef) {
        super(context);
        this.config = config;
        this.tracker = tracker;
        this.workspaceManager = workspaceManager;
        this.orchestratorRef = orchestratorRef;
    }

    public static Behavior<Command> create(AppConfig config,
                                          Tracker tracker,
                                          WorkspaceManager workspaceManager,
                                          ActorRef<Orchestrator.Command> orchestratorRef) {
        return Behaviors.setup(ctx -> new AgentRunnerTyped(ctx, config, tracker, workspaceManager, orchestratorRef));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartAgent.class, msg -> handleStartAgent(msg))
                .onMessage(StopAgent.class, msg -> handleStopAgent())
                .build();
    }

    private Behavior<Command> handleStartAgent(StartAgent msg) {
        Issue issue = msg.issue();
        String preferredHost = msg.workerHost();

        CompletableFuture.runAsync(() -> {
            logger.info("Agent run started: issue={}, worker={}",
                    issue.getIdentifier(), preferredHost != null ? preferredHost : "local");

            AgentSession session = null;
            try {
                String selectedHost = selectWorkerHost(preferredHost);
                Path workspace = workspaceManager.createForIssue(issue, selectedHost);
                session = AgentSessionFactory.create(config, workspace.toString(), selectedHost);
                session.start(event -> handleAgentEvent(issue, event));
                String processId = session.getProcessId();

                orchestratorRef.tell(new Orchestrator.WorkerRuntimeInfo(
                        issue.getId(), selectedHost, workspace.toString(), processId));

                workspaceManager.runHook(
                        config.getHooks().getBeforeRun(),
                        workspace, issue, "before_run", selectedHost);

                runAgentTurns(session, workspace, issue);

                workspaceManager.runHook(
                        config.getHooks().getAfterRun(),
                        workspace, issue, "after_run", selectedHost);

                // Stop session BEFORE sending AgentDown so pending Codex events
                // are flushed to the orchestrator's mailbox before RunningEntry is removed
                if (session != null) {
                    session.stop();
                }

                logger.info("Agent run completed: issue={}", issue.getIdentifier());
                orchestratorRef.tell(new Orchestrator.AgentDown(issue.getId(), null));
                getContext().getSelf().tell(StopAgent.INSTANCE);

            } catch (Exception e) {
                logger.error("Agent run failed: issue={}", issue.getIdentifier(), e);
                // Stop session BEFORE sending AgentDown
                if (session != null) {
                    session.stop();
                }
                orchestratorRef.tell(new Orchestrator.AgentDown(issue.getId(), e.getMessage()));
                getContext().getSelf().tell(StopAgent.INSTANCE);
            }
        });

        return Behaviors.same();
    }

    private Behavior<Command> handleStopAgent() {
        logger.info("Agent stop requested");
        return Behaviors.stopped();
    }

    private String selectWorkerHost(String preferredHost) {
        if (config.getWorker() == null) return null;
        var hosts = config.getWorker().getSshHosts();
        if (hosts == null || hosts.isEmpty()) return null;
        if (preferredHost != null && !preferredHost.isBlank()) return preferredHost;
        return hosts.get(0);
    }

    private void runAgentTurns(AgentSession session, Path workspace, Issue issue) throws Exception {
        int maxTurns = config.getAgent().getMaxTurns();
        String prompt = buildPrompt(issue);
        for (int turnNumber = 1; turnNumber <= maxTurns; turnNumber++) {
            logger.info("Executing turn: issue={}, turn={}/{}",
                    issue.getIdentifier(), turnNumber, maxTurns);
            AgentSession.TurnResult result = session.runTurn(prompt, issue);
            if (!result.isCompleted()) {
                logger.warn("Turn not completed: issue={}, turn={}, error={}",
                        issue.getIdentifier(), turnNumber, result.getError());
                break;
            }
            if (!shouldContinue(issue)) {
                logger.info("Issue no longer needs processing: issue={}", issue.getIdentifier());
                break;
            }
            prompt = buildContinuationPrompt(turnNumber, maxTurns);
        }
    }

    private void handleAgentEvent(Issue issue, AgentSession.AgentEvent event) {
        logger.debug("Agent event: issue={}, method={}, sessionId={}, tokens={}",
                issue.getIdentifier(), event.method(), event.sessionId(), event.totalTokens());

        orchestratorRef.tell(new Orchestrator.CodexUpdate(
                issue.getId(), event.timestamp(), event.method(), null,
                event.sessionId(), event.inputTokens(), event.outputTokens(),
                event.totalTokens(), event.rateLimits()));
    }

    private String buildPrompt(Issue issue) {
        String template = config.getCodex() != null ? config.getCodex().getPromptTemplate() : null;
        if (template == null || template.isBlank()) template = getDefaultPromptTemplate();

        String result = template
                .replace("{{ issue.identifier }}", issue.getIdentifier() != null ? issue.getIdentifier() : "")
                .replace("{{ issue.title }}", issue.getTitle() != null ? issue.getTitle() : "")
                .replace("{{ issue.description }}", issue.getDescription() != null ? issue.getDescription() : "No description provided.")
                .replace("{{ issue.state }}", issue.getState() != null ? issue.getState() : "")
                .replace("{{ issue.labels }}", issue.getLabels() != null ? String.join(", ", issue.getLabels()) : "")
                .replace("{{ issue.url }}", issue.getUrl() != null ? issue.getUrl() : "")
                .replace("{{ issue.priority }}", issue.getPriority() != null ? String.valueOf(issue.getPriority()) : "")
                .replace("{{ issue.branchName }}", issue.getBranchName() != null ? issue.getBranchName() : "");

        result = result.replaceAll("(?s)\\{%\\s*if\\s+attempt\\s*%\\}.*?\\{%\\s*endif\\s*%\\}", "");
        return result;
    }

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

    private String getDefaultPromptTemplate() {
        return """
                You are working on a Linear issue.

                Identifier: {{ issue.identifier }}
                Title: {{ issue.title }}

                Body:
                {{ issue.description }}
                """;
    }

    private boolean shouldContinue(Issue issue) {
        try {
            var states = tracker.fetchIssueStatesByIds(java.util.List.of(issue.getId())).toCompletableFuture().join();
            if (states.isEmpty()) return false;
            Issue latest = states.get(0);
            String state = latest.getState();
            boolean isActive = config.getTracker().getActiveStates().stream()
                    .map(String::toLowerCase)
                    .anyMatch(s -> s.equalsIgnoreCase(state.trim()));
            if (!isActive) return false;
            // Also check routability: stop if issue is no longer assigned to this worker
            // or missing required labels (matches Elixir continue_with_issue?)
            return latest.isRoutable(config.getTracker().getRequiredLabels());
        } catch (Exception e) {
            logger.warn("Check issue state failed: issue={}", issue.getIdentifier(), e);
            return false;
        }
    }

    // region Command
    public sealed interface Command permits StartAgent, StopAgent {}
    // endregion

    // region Messages
    public record StartAgent(Issue issue, String workerHost) implements Command {}
    public enum StopAgent implements Command { INSTANCE }
    // endregion
}
