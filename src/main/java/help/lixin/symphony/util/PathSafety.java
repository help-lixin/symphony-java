package help.lixin.symphony.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径安全检查工具类
 *
 * 功能说明:
 * - 验证路径不包含恶意内容
 * - 检查路径遍历攻击（../）
 * - 确保路径在允许的目录范围内
 *
 * 安全检查:
 * - 规范化路径
 * - 验证绝对路径
 * - 检查符号链接
 */
public class PathSafety {

    private PathSafety() {
        // 工具类不实例化
    }

    /**
     * 规范化并验证路径
     *
     * @param path 输入路径
     * @return 规范化后的路径
     * @throws IllegalArgumentException 如果路径不安全
     */
    public static Path canonicalize(String path) throws IllegalArgumentException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }

        Path normalized = Paths.get(path).normalize().toAbsolutePath();

        // 检查路径中是否包含null字符
        if (path.contains("\0")) {
            throw new IllegalArgumentException("路径包含无效字符");
        }

        return normalized;
    }

    /**
     * 检查路径是否在指定根目录下
     *
     * @param path 要检查的路径
     * @param rootPath 根目录路径
     * @return 是否在根目录下
     */
    public static boolean isUnderRoot(Path path, Path rootPath) {
        try {
            Path normalizedPath = path.toAbsolutePath().normalize();
            Path normalizedRoot = rootPath.toAbsolutePath().normalize();

            // 确保根目录本身不是目标路径
            if (normalizedPath.equals(normalizedRoot)) {
                return false;
            }

            // 检查路径是否以根目录开头
            return normalizedPath.startsWith(normalizedRoot);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查路径是否为符号链接
     *
     * @param path 要检查的路径
     * @return 是否为符号链接
     */
    public static boolean isSymlink(Path path) {
        try {
            return Files.isSymbolicLink(path);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 验证路径不包含路径遍历攻击
     *
     * @param path 输入路径
     * @return 是否安全
     */
    public static boolean isSafePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        // 检查包含null字符
        if (path.contains("\0")) {
            return false;
        }

        // 检查路径遍历
        String normalized = path.replace("\\", "/");

        // 检查 .. 路径遍历
        if (normalized.contains("../") || normalized.contains("..\\")) {
            return false;
        }

        return true;
    }
}
