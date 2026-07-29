package cn.bugstack.ai.test.agent.operations;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnterpriseSchemaContractTest {

    @Test
    public void migrationDefinesOperationalAndCapabilitySupplyChains() throws IOException {
        String sql = readResource("sql/mysql/migrations/V20260717__enterprise_agent_operations.sql");

        for (String table : new String[]{
                "analysis_job", "feedback_evaluation_job", "ai_signal", "case_evidence", "case_score_snapshot", "case_review_record",
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

    @Test
    public void createTablesKeepsCaseMergeColumnOnCaseTableOnly() throws IOException {
        String sql = readResource("sql/mysql/create_tables.sql");

        String feedbackTable = tableDefinition(sql, "ai_feedback");
        String caseTable = tableDefinition(sql, "ai_case");

        assertFalse("feedback table must not own case merge target", feedbackTable.contains("merged_to_case_id"));
        assertTrue("case table must own case merge target", caseTable.contains("merged_to_case_id"));
    }

    private static String tableDefinition(String sql, String tableName) {
        String marker = "create table if not exists `" + tableName + "`";
        int start = sql.indexOf(marker);
        assertTrue("missing table " + tableName, start >= 0);
        int end = sql.indexOf("engine=innodb", start);
        assertTrue("missing table end " + tableName, end > start);
        return sql.substring(start, end);
    }

    private static String readResource(String resourcePath) throws IOException {
        ClassLoader classLoader = EnterpriseSchemaContractTest.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            assertTrue("missing resource " + resourcePath, inputStream != null);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
