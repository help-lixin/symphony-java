package help.lixin.symphony.http;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.javadsl.Behaviors;
import akka.pattern.Patterns;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.orchestrator.Orchestrator;
import help.lixin.symphony.orchestrator.OrchestratorSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import scala.concurrent.Future;

/**
 * HTTP Server for Symphony observability dashboard and API.
 *
 * Provides:
 * - GET /: HTML dashboard
 * - GET /api/v1/state: Orchestrator state JSON
 * - GET /api/v1/:issueIdentifier: Single issue details JSON
 * - POST /api/v1/refresh: Trigger poll refresh
 * - GET /dashboard.css: Dashboard stylesheet
 */
public class SymphonyHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(SymphonyHttpServer.class);

    private final ActorSystem<?> typedSystem;
    private final ActorRef<Orchestrator.Command> orchestratorRef;
    private final ObjectMapper objectMapper;
    private final int snapshotTimeoutMs;
    private final AppConfig config;
    private final com.sun.net.httpserver.HttpServer server;

    public SymphonyHttpServer(ActorSystem<?> typedSystem,
                             ActorRef<Orchestrator.Command> orchestratorRef,
                             AppConfig config,
                             int port,
                             int snapshotTimeoutMs) throws IOException {
        this.typedSystem = typedSystem;
        this.orchestratorRef = orchestratorRef;
        this.config = config;
        this.snapshotTimeoutMs = snapshotTimeoutMs;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();

        this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/", new DashboardHandler());
        this.server.createContext("/dashboard.css", new CssHandler());
        this.server.createContext("/api/v1/state", new StateHandler());
        this.server.createContext("/api/v1/refresh", new RefreshHandler());
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        logger.info("HTTP server started on port {}", getPort());
    }

    public void stop() {
        server.stop(1);
        logger.info("HTTP server stopped");
    }

    public static SymphonyHttpServer start(ActorSystem<?> typedSystem,
                                          ActorRef<Orchestrator.Command> orchestratorRef,
                                          AppConfig config,
                                          int port) throws IOException {
        SymphonyHttpServer httpServer = new SymphonyHttpServer(typedSystem, orchestratorRef, config, port, 15000);
        httpServer.start();
        return httpServer;
    }

    private class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            sendResponse(exchange, 200, "text/html; charset=utf-8", DashboardHtml.render());
        }
    }

    private class CssHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            sendResponse(exchange, 200, "text/css", DashboardHtml.CSS);
        }
    }

    private class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            // Check if this is a request for a specific issue
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/api/v1/") && !path.equals("/api/v1/state") && !path.equals("/api/v1/")) {
                String issueIdentifier = path.substring(path.lastIndexOf('/') + 1);
                handleIssueRequest(exchange, issueIdentifier);
                return;
            }

            // Get overall state
            try {
                OrchestratorSnapshot snapshot = askOrchestratorSnapshot();
                Map<String, Object> payload = ObservabilityApi.statePayload(snapshot, config);
                String json = objectMapper.writeValueAsString(payload);
                sendResponse(exchange, 200, "application/json", json);
            } catch (Exception e) {
                logger.warn("Failed to get orchestrator snapshot", e);
                Map<String, Object> error = Map.of("error", Map.of("code", "snapshot_timeout", "message", "Snapshot timed out"));
                sendJsonResponse(exchange, 500, error);
            }
        }
    }

    private class RefreshHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            try {
                Orchestrator.RefreshResult result = askOrchestrator(Orchestrator.RequestRefresh.INSTANCE, Orchestrator.RefreshResult.class);
                Map<String, Object> payload = ObservabilityApi.refreshPayload(result);
                String json = objectMapper.writeValueAsString(payload);
                sendResponse(exchange, 202, "application/json", json);
            } catch (Exception e) {
                logger.warn("Failed to refresh", e);
                Map<String, Object> error = Map.of("error", Map.of("code", "orchestrator_unavailable", "message", "Orchestrator is unavailable"));
                sendJsonResponse(exchange, 503, error);
            }
        }
    }

    private void handleIssueRequest(HttpExchange exchange, String issueIdentifier) throws IOException {
        try {
            OrchestratorSnapshot snapshot = askOrchestratorSnapshot();
            Optional<Map<String, Object>> payload = ObservabilityApi.issuePayload(issueIdentifier, snapshot, config);
            if (payload.isPresent()) {
                String json = objectMapper.writeValueAsString(payload.get());
                sendResponse(exchange, 200, "application/json", json);
            } else {
                Map<String, Object> error = Map.of("error", Map.of("code", "issue_not_found", "message", "Issue not found"));
                sendJsonResponse(exchange, 404, error);
            }
        } catch (Exception e) {
            logger.warn("Failed to get issue payload", e);
            Map<String, Object> error = Map.of("error", Map.of("code", "issue_not_found", "message", "Issue not found"));
            sendJsonResponse(exchange, 404, error);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T askOrchestrator(Object message, Class<T> clazz) throws Exception {
        akka.actor.ActorSystem classicSystem = typedSystem.classicSystem();
        Future<Object> future = Patterns.ask(
            classicSystem.actorSelection("/user/orchestrator"),
            message,
            snapshotTimeoutMs
        );
        return (T) scala.concurrent.Await.result(future, scala.concurrent.duration.Duration.apply(snapshotTimeoutMs, TimeUnit.MILLISECONDS));
    }

    private OrchestratorSnapshot askOrchestratorSnapshot() throws Exception {
        akka.actor.ActorSystem classicSystem = typedSystem.classicSystem();
        CompletableFuture<OrchestratorSnapshot> replyFuture = new CompletableFuture<>();
        // Create reply adapter using typed system - spawn an actor under system guardian
        ActorRef<OrchestratorSnapshot> replyAdapter = typedSystem.systemActorOf(
            Behaviors.receiveMessage((OrchestratorSnapshot msg) -> {
                replyFuture.complete(msg);
                return Behaviors.stopped();
            }),
            "snapshot-reply-" + System.nanoTime(),
            akka.actor.typed.Props.empty()
        );
        Object adaptedMessage = new Orchestrator.Snapshot(replyAdapter);
        Patterns.ask(
            classicSystem.actorSelection("/user/orchestrator"),
            adaptedMessage,
            snapshotTimeoutMs
        );
        return replyFuture.get(snapshotTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        sendResponse(exchange, statusCode, "application/json", json);
    }
}
