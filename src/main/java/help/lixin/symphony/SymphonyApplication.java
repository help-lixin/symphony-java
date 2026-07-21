package help.lixin.symphony;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.config.ConfigLoader;
import help.lixin.symphony.dashboard.StatusDashboard;
import help.lixin.symphony.http.SymphonyHttpServer;
import help.lixin.symphony.orchestrator.Orchestrator;
import help.lixin.symphony.tracker.LinearTracker;
import help.lixin.symphony.tracker.Tracker;
import help.lixin.symphony.util.LogFile;
import help.lixin.symphony.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Symphony Akka应用主类 (Akka Typed)
 */
public class SymphonyApplication {
    private static final Logger logger = LoggerFactory.getLogger(SymphonyApplication.class);

    private static ActorSystem<Orchestrator.Command> actorSystem;
    private static ActorRef<Orchestrator.Command> orchestratorRef;
    private static SymphonyHttpServer httpServer;

    public static void start(Path workflowPath, Integer port) throws ConfigLoader.ConfigLoadException {
        logger.info("初始化Symphony应用...");

        AppConfig config = ConfigLoader.load(workflowPath);
        logger.info("配置加载完成: tracker.kind={}", config.getTracker().getKind());

        String logsRoot = System.getProperty("symphony.logs.root");
        if (logsRoot != null) {
            LogFile.configure(logsRoot);
        } else {
            LogFile.configure(config.getWorkspace().getRoot());
        }

        Tracker tracker = createTracker(config);
        WorkspaceManager workspaceManager = new WorkspaceManager(config);

        // Create a setup behavior that creates all actors
        Behavior<Orchestrator.Command> rootBehavior = Behaviors.setup(ctx -> {
            // Create Orchestrator first
            ActorRef<Orchestrator.Command> orch = ctx.spawn(
                    Orchestrator.create(config, tracker, workspaceManager, null),
                    "orchestrator"
            );
            orchestratorRef = orch;
            logger.info("Orchestrator Actor已创建");

            // Create StatusDashboard with orchestrator ref
            ActorRef<StatusDashboard.Command> dashboard = ctx.spawn(
                    StatusDashboard.create(config, orch),
                    "statusDashboard"
            );
            logger.info("StatusDashboard Actor已创建");

            // Register dashboard with orchestrator
            orch.tell(new Orchestrator.RegisterDashboard(dashboard));

            // Start Orchestrator
            orch.tell(Orchestrator.Start.INSTANCE);

            // Start HTTP server if port is configured
            startHttpServer(ctx.getSystem(), orch, config, port);

            // Return empty behavior - this guardian doesn't need to handle messages
            return Behaviors.empty();
        });

        // Create ActorSystem
        actorSystem = ActorSystem.create(rootBehavior, "symphony");

        logger.info("Symphony应用启动完成");
    }

    private static void startHttpServer(ActorSystem<?> system,
                                         ActorRef<Orchestrator.Command> orchestratorRef,
                                         AppConfig config,
                                         Integer port) {
        // Determine port: CLI --port takes priority, then config server.port
        int httpPort;
        if (port != null && port >= 0) {
            httpPort = port;
        } else if (config.getServer() != null && config.getServer().getPort() != null) {
            httpPort = config.getServer().getPort();
        } else {
            logger.info("HTTP server not configured (no --port argument and no server.port in WORKFLOW.md)");
            return;
        }

        logger.info("启动HTTP服务器 on port {}", httpPort);

        try {
            httpServer = SymphonyHttpServer.start(system, orchestratorRef, config, httpPort);
        } catch (IOException e) {
            logger.error("HTTP server启动失败", e);
        }
    }

    public static void stop() {
        logger.info("停止Symphony应用...");

        // Stop HTTP server first
        if (httpServer != null) {
            httpServer.stop();
        }

        if (actorSystem != null) {
            try {
                actorSystem.terminate();
                actorSystem.getWhenTerminated().toCompletableFuture().get(10, TimeUnit.SECONDS);
                logger.info("Akka Actor系统已终止");
            } catch (Exception e) {
                logger.warn("等待Actor系统终止时出错", e);
            }
        }

        logger.info("Symphony应用已停止");
    }

    public static ActorRef<Orchestrator.Command> getOrchestratorRef() {
        return orchestratorRef;
    }

    public static ActorSystem<Orchestrator.Command> getActorSystem() {
        return actorSystem;
    }

    private static Tracker createTracker(AppConfig config) {
        String trackerKind = config.getTracker().getKind();

        if ("memory".equalsIgnoreCase(trackerKind)) {
            logger.info("创建内存追踪器");
            return new help.lixin.symphony.tracker.MemoryTracker();
        } else {
            logger.info("创建Linear追踪器: endpoint={}", config.getTracker().getEndpoint());
            return new LinearTracker(config);
        }
    }
}
