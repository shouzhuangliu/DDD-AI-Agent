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
        assertTrue(sql.contains("v4-evidence-gate"));
    }

    @Test
    public void displayTextRepairMigrationUsesUtf8SafeIdempotentUpdates() throws IOException {
        String sql = readResource("sql/mysql/migrations/V20260804__repair_display_text_encoding.sql");

        assertTrue(sql.contains("set names utf8mb4"));
        assertTrue(sql.contains("convert(0x"), "repair values must be encoded as hex to survive a non-UTF-8 mysql client");
        assertTrue(sql.contains("agent_id` = 'auto_agent'"));
        assertTrue(sql.contains("agent_id` = 'feedback-ops-agent'"));
        assertTrue(sql.contains("mcp_key` = 'feedback-ops-mcp'"));
        assertTrue(sql.contains("prompt_id` = '5001'"));
        assertTrue(sql.contains("prompt_content` like '%?%'"), "runtime prompt bodies must be repaired together with display labels");
        assertTrue(sql.contains("like '%?%'"), "repair must not overwrite already-correct user-edited text");
    }

    @Test
    public void businessSignalMigrationSeparatesNonEligibleAndRuntimeObservations() throws IOException {
        String sql = readResource("sql/mysql/migrations/V20260805__business_signal_visibility.sql");

        assertTrue(sql.contains("decision = 'not_eligible'"));
        assertTrue(sql.contains("status = 'suppressed'"));
        assertTrue(sql.contains("runtime_observation"));
        assertTrue(sql.contains("tool_failure"));
    }

    @Test
    public void businessSignalQueriesDeclareOuterAliasForSnapshotCorrelation() throws IOException {
        String xml = readResource("mybatis/mapper/ai_signal_mapper.xml");

        assertTrue(xml.contains("select s.* from ai_signal s"),
                "business signal query must declare the outer signal alias");
        assertTrue(xml.contains("e.agent_id=s.agent_id"),
                "snapshot correlation must reference the declared signal alias");
        assertTrue(xml.contains("from ai_signal s\n    where s.agent_id=#{agentid}"),
                "count query must also use the same outer alias");
    }

    @Test
    public void businessBoundaryMigrationQuarantinesUnboundFeedbackAndCases() throws IOException {
        String sql = readResource("sql/mysql/migrations/V20260806__business_boundary.sql");

        assertTrue(sql.contains("unbound_agent"));
        assertTrue(sql.contains("archived"));
        assertTrue(sql.contains("ai_agent_skill"));
        assertTrue(!sql.contains("ai_agent_mcp"),
                "business-boundary migration must not treat MCP as a Case prerequisite");

        String repair = readResource("sql/mysql/migrations/V20260807__restore_cases_archived_for_optional_mcp.sql");
        assertTrue(repair.contains("mcp 不是 case 必选条件"));
    }

    @Test
    public void businessDashboardsRequireActiveSkillBindings() throws IOException {
        String feedback = readResource("mybatis/mapper/ai_feedback_mapper.xml");
        String cases = readResource("mybatis/mapper/ai_case_mapper.xml");

        assertTrue(feedback.contains("exists (select 1 from ai_agent_skill sk"),
                "feedback dashboard queries must exclude agents without an active business Skill");
        assertTrue(cases.contains("exists (select 1 from ai_agent_skill sk"),
                "case dashboard queries must require an active Skill binding");
        assertTrue(!cases.contains("exists (select 1 from ai_agent_mcp mc"),
                "MCP availability must not be a prerequisite for a business Case");
    }

    @Test
    public void devProfileMatchesProjectDockerMysqlDefaults() throws IOException {
        String yaml = readResource("application-dev.yml");

        assertTrue(yaml.contains("${mysql_port:13306}"),
                "IDEA profile must use the project Docker MySQL port by default");
        assertTrue(yaml.contains("${mysql_password:123456}"),
                "IDEA profile must use the project Docker root password by default");
        assertTrue(yaml.contains("createdatabaseifnotexist=true"),
                "IDEA profile must be able to create the local schema on first connection");
        assertTrue(yaml.contains("characterencoding=utf-8"),
                "JDBC characterEncoding must use a Java charset name");
        assertTrue(yaml.contains("connectioncollation=utf8mb4_0900_ai_ci"),
                "MySQL utf8mb4 collation must be configured independently from the Java charset");
        assertTrue(yaml.contains("charset: utf-8"),
                "HTTP responses must declare UTF-8 for non-browser clients");
        assertTrue(yaml.contains("force-response: true"),
                "HTTP encoding must be forced on responses");
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
