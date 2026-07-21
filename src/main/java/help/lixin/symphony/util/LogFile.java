package help.lixin.symphony.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志文件管理工具类
 *
 * 功能说明:
 * - 配置日志文件路径
 * - 创建日志目录
 * - 支持日志轮转（基于日期）
 *
 * 日志结构:
 * log_root/symphony.log
 * log_root/symphony.YYYY-MM-DD.log
 */
public class LogFile {
    private static final Logger logger = LoggerFactory.getLogger(LogFile.class);

    private static final String LOG_FILE_NAME = "symphony.log";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static String logRoot;

    private LogFile() {
        // 工具类不实例化
    }

    /**
     * 配置日志目录
     *
     * @param root 日志根目录
     */
    public static void configure(String root) {
        if (root == null || root.isBlank()) {
            root = System.getProperty("java.io.tmpdir");
        }

        // 解析~
        if (root.startsWith("~")) {
            root = System.getProperty("user.home") + root.substring(1);
        }

        // 解析环境变量
        root = resolveEnvVar(root);

        logRoot = root;

        // 创建日志目录
        try {
            Files.createDirectories(Path.of(root));
            logger.info("日志目录已配置: {}", root);
        } catch (IOException e) {
            logger.warn("创建日志目录失败: {}", root, e);
        }
    }

    /**
     * 获取日志根目录
     *
     * @return 日志根目录
     */
    public static String getLogRoot() {
        if (logRoot == null) {
            configure(System.getProperty("java.io.tmpdir"));
        }
        return logRoot;
    }

    /**
     * 获取当前日志文件路径
     *
     * @return 日志文件路径
     */
    public static Path getLogFilePath() {
        return Path.of(getLogRoot(), LOG_FILE_NAME);
    }

    /**
     * 获取带日期的日志文件路径
     *
     * @param date 日期
     * @return 日志文件路径
     */
    public static Path getDatedLogFilePath(LocalDate date) {
        String filename = String.format("symphony.%s.log", date.format(DATE_FORMAT));
        return Path.of(getLogRoot(), filename);
    }

    /**
     * 解析环境变量引用
     */
    private static String resolveEnvVar(String value) {
        if (value == null) return null;
        Pattern pattern = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?");
        return pattern.matcher(value).replaceAll(match -> {
            String envName = match.group(1);
            return System.getenv(envName) != null ? System.getenv(envName) : match.group();
        });
    }

    /**
     * 默认日志文件路径（带日期）
     *
     * @return 带日期的日志文件路径
     */
    public static String defaultLogFile() {
        return getDatedLogFilePath(LocalDate.now()).toString();
    }

    /**
     * 默认日志目录下的日志文件
     *
     * @param logsRoot 日志根目录
     * @return 日志文件路径
     */
    public static String defaultLogFile(String logsRoot) {
        if (logsRoot == null || logsRoot.isBlank()) {
            return defaultLogFile();
        }
        return Path.of(logsRoot, LOG_FILE_NAME).toString();
    }
}
