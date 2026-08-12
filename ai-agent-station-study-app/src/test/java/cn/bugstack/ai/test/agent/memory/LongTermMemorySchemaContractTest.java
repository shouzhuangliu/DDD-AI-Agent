package cn.bugstack.ai.test.agent.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemorySchemaContractTest {

    @Test
    void migrationDefinesGovernedMemoryTablesAndAgentScopedKeys() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/sql/mysql/migrations/"
                + "V20260812__agent_long_term_memory_governance.sql"));

        for (String table : new String[]{"agent_memory_candidate", "agent_memory_evidence",
                "agent_memory_card", "agent_memory_extraction_cursor", "agent_memory_index_outbox"}) {
            assertTrue(sql.contains(table), "缺少长期记忆表: " + table);
        }
        assertTrue(sql.contains("uk_memory_candidate_source"));
        assertTrue(sql.contains("uk_memory_card_version"));
        assertTrue(sql.contains("uk_memory_cursor"));
        assertTrue(sql.contains("uk_memory_outbox_event"));
        assertTrue(sql.contains("idx_memory_card_lookup"));
    }
}
