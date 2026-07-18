package cn.bugstack.ai.test.agent.operations;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class EnterpriseSchemaContractTest {

    @Test
    public void migrationDefinesOperationalAndCapabilitySupplyChains() throws IOException {
        Path migration = Path.of("scripts/migrations/V20260717__enterprise_agent_operations.sql");
        if (!Files.exists(migration)) migration = Path.of("../scripts/migrations/V20260717__enterprise_agent_operations.sql");
        String sql = Files.readString(migration).toLowerCase();

        for (String table : new String[]{
                "analysis_job", "ai_signal", "case_evidence", "case_score_snapshot", "case_review_record",
                "memory_summary", "memory_state", "memory_tool_result",
                "mcp_server", "mcp_version", "mcp_test_run", "mcp_review", "mcp_release",
                "skill_package", "skill_version", "skill_validation", "skill_review", "skill_release",
                "audit_log"}) {
            assertTrue("missing table " + table, sql.contains("table if not exists `" + table + "`"));
        }
        assertTrue(sql.contains("`agent_id` varchar"));
        assertTrue(sql.contains("`assistant_message_id` bigint"));
        assertTrue(sql.contains("`source_type` varchar"));
        assertTrue(sql.contains("`idempotency_key` varchar"));
        assertTrue(sql.contains("unique key `uk_analysis_idempotency`"));
        assertTrue(sql.contains("`artifact_sha256` varchar"));
        assertTrue(sql.contains("`credential_ref` varchar"));
    }
}
