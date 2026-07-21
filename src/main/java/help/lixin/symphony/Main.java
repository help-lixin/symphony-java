package help.lixin.symphony;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import help.lixin.symphony.config.ConfigLoader;
import help.lixin.symphony.dashboard.StatusDashboard;
import help.lixin.symphony.orchestrator.Orchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Symphony CLI入口点
 *
 * 功能说明:
 * - 解析命令行参数
 * - 显示警告横幅（未经安全防护运行）
 * - 加载WORKFLOW.md配置
 * - 启动Akka Actor系统
 * - 等待shutdown信号
 *
 * 使用方式:
 * ./bin/symphony [path-to-WORKFLOW.md] [--i-understand-that-this-will-be-running-without-the-usual-guardrails]
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * 命令行参数定义
     */
    static class Args {
        @Parameter(names = "--i-understand-that-this-will-be-running-without-the-usual-guardrails",
                   description = "确认已知安全风险")
        boolean acknowledgeGuardrails = false;

        @Parameter(names = "--logs-root", description = "日志目录路径")
        String logsRoot;

        @Parameter(names = "--port", description = "HTTP服务端口")
        Integer port;

        @Parameter(description = "WORKFLOW.md文件路径")
        String workflowPath;
    }

    /**
     * 主入口方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        Args parsedArgs = new Args();

        try {
            JCommander.newBuilder()
                    .addObject(parsedArgs)
                    .build()
                    .parse(args);
        } catch (ParameterException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println("用法: symphony [--logs-root <path>] [--port <port>] [path-to-WORKFLOW.md]");
            System.exit(1);
        }

        // 检查安全确认参数
        if (!parsedArgs.acknowledgeGuardrails) {
            System.out.println(displayAcknowledgementBanner());
            System.exit(1);
        }

        // 确定WORKFLOW.md路径
        String workflowPath = parsedArgs.workflowPath;
        if (workflowPath == null || workflowPath.isBlank()) {
            workflowPath = "WORKFLOW.md";
        }

        Path resolvedPath = Paths.get(workflowPath).toAbsolutePath().normalize();

        if (!Files.isRegularFile(resolvedPath)) {
            System.err.println("错误: 找不到WORKFLOW.md文件: " + resolvedPath);
            System.exit(1);
        }

        // 设置日志目录
        if (parsedArgs.logsRoot != null && !parsedArgs.logsRoot.isBlank()) {
            System.setProperty("symphony.logs.root", parsedArgs.logsRoot);
        }

        // 设置HTTP端口
        if (parsedArgs.port != null) {
            System.setProperty("symphony.server.port", String.valueOf(parsedArgs.port));
        }

        // 设置WORKFLOW.md路径
        System.setProperty("symphony.workflow.path", resolvedPath.toString());

        logger.info("Symphony启动中...");
        logger.info("使用WORKFLOW.md: {}", resolvedPath);

        // 创建CountDownLatch用于等待shutdown
        CountDownLatch latch = new CountDownLatch(1);

        // 添加shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到shutdown信号，正在关闭...");
            latch.countDown();
        }));

        try {
            // 启动Symphony应用
            SymphonyApplication.start(resolvedPath, parsedArgs.port);

            // 等待shutdown
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("启动被中断", e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Symphony启动失败", e);
            System.exit(1);
        }

        logger.info("Symphony已关闭");
        System.exit(0);
    }

    /**
     * 显示安全警告横幅
     *
     * @return 格式化的警告文本
     */
    private static String displayAcknowledgementBanner() {
        String[] lines = {
            "此Symphony实现为工程预览版本。",
            "Codex将在没有任何安全防护的情况下运行。",
            "Symphony不提供任何支持担保，按原样提供。",
            "要继续运行，请使用 --i-understand-that-this-will-be-running-without-the-usual-guardrails CLI参数"
        };

        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, line.length());
        }

        String border = "─".repeat(maxWidth + 2);

        StringBuilder sb = new StringBuilder();
        sb.append("\033[31m\033[1m"); // 红色高亮
        sb.append("╭").append(border).append("╮\n");
        sb.append("│ ").append(" ".repeat(maxWidth)).append(" │\n");

        for (String line : lines) {
            sb.append("│ ").append(String.format("%-" + maxWidth + "s", line)).append(" │\n");
        }

        sb.append("│ ").append(" ".repeat(maxWidth)).append(" │\n");
        sb.append("╰").append(border).append("╯\n");
        sb.append("\033[0m"); // 重置颜色

        return sb.toString();
    }
}
