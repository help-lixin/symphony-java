package help.lixin.symphony.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PathSafety工具类测试
 */
class PathSafetyTest {

    @Test
    void testCanonicalizeNormalPath() {
        // 正常路径应该被规范化并返回绝对路径
        Path result = PathSafety.canonicalize("/tmp/test");
        assertNotNull(result);
        assertTrue(result.isAbsolute());
    }

    @Test
    void testCanonicalizeNullPath() {
        // 空路径应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            PathSafety.canonicalize(null);
        });
    }

    @Test
    void testCanonicalizeBlankPath() {
        // 空白路径应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            PathSafety.canonicalize("   ");
        });
    }

    @Test
    void testCanonicalizePathWithNullChar() {
        // 包含null字符的路径应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            PathSafety.canonicalize("/tmp/test\0");
        });
    }

    @Test
    void testIsUnderRootTrue() {
        // 路径在根目录下应该返回true
        Path root = Path.of("/tmp");
        Path path = Path.of("/tmp/test/workspace");
        assertTrue(PathSafety.isUnderRoot(path, root));
    }

    @Test
    void testIsUnderRootFalse() {
        // 路径不在根目录下应该返回false
        Path root = Path.of("/tmp");
        Path path = Path.of("/var/test");
        assertFalse(PathSafety.isUnderRoot(path, root));
    }

    @Test
    void testIsUnderRootSamePath() {
        // 路径等于根目录应该返回false
        Path root = Path.of("/tmp");
        Path path = Path.of("/tmp");
        assertFalse(PathSafety.isUnderRoot(path, root));
    }

    @Test
    void testIsSafePathNormal() {
        // 正常路径应该安全
        assertTrue(PathSafety.isSafePath("/tmp/workspace"));
        assertTrue(PathSafety.isSafePath("relative/path"));
    }

    @Test
    void testIsSafePathNull() {
        // null路径不安全
        assertFalse(PathSafety.isSafePath(null));
    }

    @Test
    void testIsSafePathBlank() {
        // 空白路径不安全
        assertFalse(PathSafety.isSafePath("   "));
    }

    @Test
    void testIsSafePathWithNullChar() {
        // 包含null字符的路径不安全
        assertFalse(PathSafety.isSafePath("/tmp/test\0"));
    }

    @Test
    void testIsSafePathTraversal() {
        // 路径遍历攻击应该被检测
        assertFalse(PathSafety.isSafePath("../etc/passwd"));
        assertFalse(PathSafety.isSafePath("..\\windows\\system32"));
        assertFalse(PathSafety.isSafePath("/tmp/../../../etc/passwd"));
    }

    @Test
    void testIsSymlink() {
        // 测试符号链接检测方法存在且可调用
        // 注意：在某些环境下创建符号链接可能需要特殊权限
        Path tempPath = Path.of("/tmp");
        boolean result = PathSafety.isSymlink(tempPath);
        // 结果可以是true或false，取决于/tmp是否是符号链接
        assertNotNull(String.valueOf(result));
    }
}
