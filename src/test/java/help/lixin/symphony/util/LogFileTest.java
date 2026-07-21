package help.lixin.symphony.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LogFile日志文件工具测试
 */
class LogFileTest {

    @TempDir
    Path tempDir;

    @Test
    void testConfigure() {
        // 配置日志根目录
        String rootPath = tempDir.toString();
        LogFile.configure(rootPath);

        // 验证日志根目录设置成功
        String logRoot = LogFile.getLogRoot();
        assertNotNull(logRoot);
    }

    @Test
    void testGetLogRoot() {
        LogFile.configure(tempDir.toString());
        String logRoot = LogFile.getLogRoot();
        assertNotNull(logRoot);
    }

    @Test
    void testDefaultLogFile() {
        LogFile.configure(tempDir.toString());

        String defaultLog = LogFile.defaultLogFile();
        assertNotNull(defaultLog);
    }

    @Test
    void testDefaultLogFileWithRoot() {
        String defaultLog = LogFile.defaultLogFile(tempDir.toString());
        assertNotNull(defaultLog);
    }

    @Test
    void testGetLogFilePath() {
        LogFile.configure(tempDir.toString());

        Path logPath = LogFile.getLogFilePath();
        assertNotNull(logPath);
    }

    @Test
    void testGetDatedLogFilePath() {
        Path logPath = LogFile.getDatedLogFilePath(java.time.LocalDate.now());
        assertNotNull(logPath);
    }
}
