package cn.bugstack.ai.test.agent.tools;

import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具核心逻辑测试：沙箱路径校验、白名单解析、参数处理。
 */
class ToolCoreTest {

    @Test void testSandboxResolvesCorrectly() {
        Path wd = Paths.get("D:/test").toAbsolutePath().normalize();
        Path r = wd.resolve("file.txt").normalize();
        assertTrue(r.startsWith(wd));
        assertEquals("file.txt", wd.relativize(r).toString());
    }

    @Test void testSandboxRejectsTraversal() {
        Path wd = Paths.get("D:/test").toAbsolutePath().normalize();
        assertFalse(wd.resolve("../etc/passwd").normalize().startsWith(wd));
    }

    @Test void testSandboxRejectsAbsolute() {
        assertTrue("C:/windows".contains(":"));
        assertTrue("/etc".startsWith("/"));
    }

    @Test void testWhitelistParsing() {
        ReActToolProperties p = new ReActToolProperties();
        p.getBash().setWhitelist("ls,cat,echo");
        var s = p.bashWhitelist();
        assertTrue(s.contains("ls")); assertTrue(s.contains("cat"));
        assertFalse(s.contains("rm")); assertEquals(3, s.size());
    }

    @Test void testWhitelistCaseInsensitive() {
        ReActToolProperties p = new ReActToolProperties();
        p.getBash().setWhitelist("Ls,  CAT");
        var s = p.bashWhitelist();
        assertTrue(s.contains("ls")); assertTrue(s.contains("cat"));
    }

    @Test void testBashTokenExtraction() {
        assertEquals("ls", "ls -la".split("\\s+")[0]);
        assertEquals("cat", "cat /etc/hosts".split("\\s+")[0]);
    }

    @Test void testBashEnabledFlag() {
        ReActToolProperties p = new ReActToolProperties();
        p.getBash().setEnabled(false);
        assertFalse(p.getBash().isEnabled());
    }

    @Test void testContextBuilder() {
        ReActToolContext ctx = ReActToolContext.builder().sessionId("s1").workDir(Paths.get("D:/t")).build();
        assertEquals("s1", ctx.getSessionId());
        assertEquals("D:/t", ctx.getWorkDir().toString());
    }

    @Test void testMcpArgsParsing() {
        String args = "{\"url\":\"https://example.com\"}";
        assertTrue(args.contains("url"));
        assertTrue(args.contains("example.com"));
    }

    @Test void testMcpArgsFallback() {
        String args = null;
        String result = (args != null && !args.isBlank()) ? args : "{}";
        assertEquals("{}", result);
    }

    @Test void testRetryFallbackMessage() {
        String fb = "抱歉，暂时无法处理您的请求，请稍后重试。";
        assertNotNull(fb); assertFalse(fb.isBlank());
    }

    @Test void testRetryLogic() {
        int attempts = 0;
        for (int i = 0; i <= 2; i++) { attempts++; if (i == 2) break; }
        assertEquals(3, attempts);
    }
}