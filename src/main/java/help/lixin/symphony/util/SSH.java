package help.lixin.symphony.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SSH远程执行工具类
 *
 * 功能说明:
 * - 通过SSH执行远程命令
 * - 支持超时控制
 * - 返回命令输出和退出码
 *
 * 使用方式:
 * - 用于在远程worker主机上执行命令
 * - 支持工作区创建、删除等操作
 */
public class SSH {
    private static final Logger logger = LoggerFactory.getLogger(SSH.class);

    private SSH() {
        // 工具类不实例化
    }

    /**
     * 在远程主机上执行命令
     *
     * @param host SSH主机
     * @param command 要执行的命令
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 执行结果
     * @throws SSHException 如果执行失败
     * @throws TimeoutException 如果执行超时
     */
    public static Result run(String host, String command, long timeout, TimeUnit unit)
            throws SSHException, TimeoutException {
        logger.debug("SSH执行: host={}, command={}", host, command);

        String[] sshCommand = {
                "ssh",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                host,
                command
        };

        try {
            ProcessBuilder pb = new ProcessBuilder(sshCommand);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(timeout, unit);

            if (!completed) {
                process.destroyForcibly();
                throw new TimeoutException("SSH命令执行超时: " + host);
            }

            int exitCode = process.exitValue();

            logger.debug("SSH完成: host={}, exitCode={}", host, exitCode);

            return new Result(output.toString(), exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SSHException("SSH执行被中断: " + host, e);
        } catch (IOException e) {
            throw new SSHException("SSH执行失败: " + host, e);
        }
    }

    /**
     * 启动远程端口转发
     *
     * @param host SSH主机
     * @param localPort 本地端口
     * @param remotePort 远程端口
     * @return SSH进程
     * @throws SSHException 如果启动失败
     */
    public static Process startPortForwarding(String host, int localPort, int remotePort)
            throws SSHException {
        String[] sshCommand = {
                "ssh",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-L", localPort + ":localhost:" + remotePort,
                "-N",
                "-f",
                host
        };

        try {
            Process process = new ProcessBuilder(sshCommand).start();
            return process;
        } catch (IOException e) {
            throw new SSHException("启动端口转发失败: " + host, e);
        }
    }

    /**
     * SSH执行结果
     */
    public record Result(String output, int exitCode) {

        /**
         * 检查是否成功
         *
         * @return 是否成功（exitCode为0）
         */
        public boolean isSuccess() {
            return exitCode == 0;
        }

        /**
         * 获取输出（去除尾部空白）
         *
         * @return 清理后的输出
         */
        public String getOutput() {
            return output != null ? output.trim() : "";
        }
    }

    /**
     * SSH异常
     */
    public static class SSHException extends Exception {
        public SSHException(String message) {
            super(message);
        }

        public SSHException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
