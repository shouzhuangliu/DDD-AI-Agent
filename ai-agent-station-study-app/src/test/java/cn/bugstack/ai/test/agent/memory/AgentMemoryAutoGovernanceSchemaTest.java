package cn.bugstack.ai.test.agent.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryAutoGovernanceSchemaTest {

    @Test
    void autoGovernanceMigrationDefinesSoftDeleteAndChangeAudit() throws Exception {
        Path migration = Path.of("src/main/resources/sql/mysql/migrations/V20260813__agent_memory_auto_governance.sql");
        assertTrue(Files.exists(migration), "自动治理迁移必须存在");
        String sql = Files.readString(migration);
        assertTrue(sql.contains("is_deleted"));
        assertTrue(sql.contains("importance"));
        assertTrue(sql.contains("pinned"));
        assertTrue(sql.contains("agent_memory_change_log"));
    }
}
