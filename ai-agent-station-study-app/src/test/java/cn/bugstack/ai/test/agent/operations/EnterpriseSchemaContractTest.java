package cn.bugstack.ai.test.agent.operations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertTrue(sql.contains("table if not exists `" + table + "`"), "missing table " + table);
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

        assertFalse(feedbackTable.contains("merged_to_case_id"), "feedback table must not own case merge target");
        assertTrue(caseTable.contains("merged_to_case_id"), "case table must own case merge target");
    }

    @Test
    public void latestAnalysisJobMigrationMustBeSelfContained() throws IOException {
        String sql = readResource("sql/mysql/migrations/V20260802__conversation_analysis_event_idle.sql");

        assertFalse(sql.contains("call add_column_if_missing"),
                "latest migration must not call a procedure dropped by the previous migration");
        assertTrue(sql.contains("available_at"),
                "latest migration must add the event-idle scheduling column");
    }

    @Test
    public void caseEvidenceGateMigrationStoresImmutableEvaluationAndEvidenceFields() throws IOException {
        String sql = readResource("sql/mysql/migrations/V20260803__case_evidence_gate.sql");

        assertTrue(sql.contains("case_evaluation_snapshot"));
        assertTrue(sql.contains("evidence_fingerprint"));
        assertTrue(sql.contains("evidence_role"));
        assertTrue(sql.contains("skill_rule_id"));
        assertTrue(sql.contains("supports_json"));
        assertTrue(sql.contains("uk_case_evaluation_idempotency"));
    }

    @Test
    public void devProfileMatchesNativeMysqlDefaults() throws IOException {
        String yaml = readResource("application-dev.yml");

        assertTrue(yaml.contains("${mysql_port:3306}"),
                "IDEA profile must use the native MySQL port by default");
        assertTrue(yaml.contains("${mysql_password:1234}"),
                "IDEA profile must use the local root password by default");
        assertTrue(yaml.contains("createdatabaseifnotexist=true"),
                "IDEA profile must be able to create the local schema on first connection");
    }

    private static String tableDefinition(String sql, String tableName) {
        String marker = "create table if not exists `" + tableName + "`";
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "missing table " + tableName);
        int end = sql.indexOf("engine=innodb", start);
        assertTrue(end > start, "missing table end " + tableName);
        return sql.substring(start, end);
    }

    private static String readResource(String resourcePath) throws IOException {
        ClassLoader classLoader = EnterpriseSchemaContractTest.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            assertTrue(inputStream != null, "missing resource " + resourcePath);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
