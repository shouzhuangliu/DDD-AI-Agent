package cn.bugstack.ai.trigger.service.capability;

import cn.bugstack.ai.domain.agent.service.capability.CapabilityApprovalPolicy;
import cn.bugstack.ai.domain.agent.service.operations.WorkflowTransitionPolicy;
import cn.bugstack.ai.trigger.service.capability.skill.SafeSkillArchiveValidator;
import cn.bugstack.ai.domain.agent.service.armory.AiClientToolMcpNode;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class CapabilityRegistryService {

    private final JdbcTemplate jdbc;
    private final SafeSkillArchiveValidator archiveValidator;
    private final AiClientToolMcpNode mcpNode;
    private final WorkflowTransitionPolicy transitions = new WorkflowTransitionPolicy();
    private final CapabilityApprovalPolicy approvals = new CapabilityApprovalPolicy();

    @Value("${agent.capability.storage-dir:data/capabilities}") private String storageDir;
    @Value("${spring.ai.agent.react.skills-dir:${user.dir}/skills}") private String runtimeSkillsDir;

    public CapabilityRegistryService(@Qualifier("mysqlDataSource") DataSource dataSource,
                                     SafeSkillArchiveValidator archiveValidator,
                                     AiClientToolMcpNode mcpNode) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.archiveValidator = archiveValidator;
        this.mcpNode = mcpNode;
    }

    public long registerMcp(Map<String, Object> body, String actor) {
        String key = required(body, "mcpKey"), name = required(body, "name");
        jdbc.update("INSERT INTO mcp_server (mcp_key,name,description,owner,status) VALUES (?,?,?,?, 'DRAFT')",
                key, name, text(body, "description"), actor);
        long id = jdbc.queryForObject("SELECT id FROM mcp_server WHERE mcp_key=?", Long.class, key);
        audit("MCP_SERVER", String.valueOf(id), "REGISTER", actor, "DEVELOPER", null, null, JSON.toJSONString(body));
        return id;
    }

    public long seedLocalTestMcp() {
        jdbc.update("""
                INSERT INTO mcp_server (mcp_key, name, description, owner, status)
                VALUES ('local-test-mcp', '本地测试 MCP', '用于测试 echo、add、current_time 工具的本地 STDIO MCP', 'local-system', 'DRAFT')
                ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description)
                """);
        long serverId = jdbc.queryForObject("SELECT id FROM mcp_server WHERE mcp_key='local-test-mcp'", Long.class);
        jdbc.update("""
                INSERT INTO mcp_version (server_id, version, transport_type, endpoint_config, credential_ref, submitted_by)
                VALUES (?, '1.0.0', 'stdio', ?, '', 'local-system')
                ON DUPLICATE KEY UPDATE endpoint_config=VALUES(endpoint_config), transport_type='stdio'
                """, serverId, "{\"command\":\"python\",\"args\":[\"D:/javacode/ai-agent/ai-agent-station-study/mcp-test-server/test_mcp_server.py\"]}");
        long versionId = jdbc.queryForObject("SELECT id FROM mcp_version WHERE server_id=? AND version='1.0.0'", Long.class, serverId);
        jdbc.update("""
                INSERT INTO mcp_discovered_tool (version_id, tool_name, description, input_schema, risk_level, enabled)
                VALUES (?, 'echo', 'Return the supplied text.', ?, 'LOW', 1),
                       (?, 'add', 'Add two numbers.', ?, 'LOW', 1),
                       (?, 'current_time', 'Return the current UTC time.', ?, 'LOW', 1)
                ON DUPLICATE KEY UPDATE description=VALUES(description), input_schema=VALUES(input_schema), enabled=1
                """, versionId, "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}",
                versionId, "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},\"required\":[\"a\",\"b\"]}",
                versionId, "{\"type\":\"object\",\"properties\":{}}") ;
        return versionId;
    }

    public long createMcpVersion(long serverId, Map<String, Object> body, String actor) {
        String version = required(body, "version"), transport = required(body, "transportType").toLowerCase(Locale.ROOT);
        if (!Set.of("sse", "stdio", "streamable-http").contains(transport)) throw new IllegalArgumentException("Unsupported MCP transport");
        jdbc.update("INSERT INTO mcp_version (server_id,version,transport_type,endpoint_config,credential_ref,timeout_seconds,retry_count,concurrency_limit,status,submitted_by) VALUES (?,?,?,?,?,?,?,?, 'DRAFT',?)",
                serverId, version, transport, required(body, "endpointConfig"), text(body, "credentialRef"),
                number(body, "timeoutSeconds", 60), number(body, "retryCount", 2), number(body, "concurrencyLimit", 10), actor);
        long id = jdbc.queryForObject("SELECT id FROM mcp_version WHERE server_id=? AND version=?", Long.class, serverId, version);
        audit("MCP_VERSION", String.valueOf(id), "CREATE_VERSION", actor, "DEVELOPER", null, null, "version=" + version);
        return id;
    }

    public Map<String, Object> testMcpConnectivity(long versionId, String actor) {
        Map<String, Object> version = one("SELECT id,transport_type,endpoint_config,status FROM mcp_version WHERE id=?", versionId);
        String transport = String.valueOf(version.get("transport_type"));
        String config = String.valueOf(version.get("endpoint_config"));
        JSONObject json = JSON.parseObject(config);
        boolean passed;
        String detail;
        if ("sse".equals(transport) || "streamable-http".equals(transport)) {
            String endpoint = Optional.ofNullable(json.getString("baseUri")).orElse(json.getString("url"));
            if (endpoint == null) throw new IllegalArgumentException("HTTP MCP requires baseUri or url");
            java.net.URI uri = java.net.URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme())) throw new IllegalArgumentException("MCP URL must use HTTP(S)");
            passed = uri.getHost() != null;
            detail = "Endpoint syntax and host validated; protocol discovery is the next gate";
        } else {
            String command = json.getString("command");
            if (command == null && json.getJSONObject("stdio") != null) command = "declared-stdio";
            passed = command != null && !command.matches(".*[;&|`].*");
            detail = "STDIO command passed pre-execution sandbox policy";
        }
        jdbc.update("INSERT INTO mcp_test_run (version_id,run_type,status,response_json,duration_ms,executed_by) VALUES (?,'CONNECTIVITY',?,?,0,?)",
                versionId, passed ? "PASSED" : "FAILED", JSON.toJSONString(Map.of("detail", detail)), actor);
        if (passed) transition("mcp_version", versionId, WorkflowTransitionPolicy.Resource.MCP, "CONNECTIVITY_CHECKED");
        audit("MCP_VERSION", String.valueOf(versionId), "CONNECTIVITY_TEST", actor, "TESTER", null, null, detail);
        return Map.of("passed", passed, "detail", detail);
    }

    public void recordMcpDiscovery(long versionId, List<Map<String, Object>> tools, String actor) {
        requireState("mcp_version", versionId, "CONNECTIVITY_CHECKED");
        if (tools == null) throw new IllegalArgumentException("tools is required");
        for (Map<String, Object> tool : tools) {
            jdbc.update("INSERT INTO mcp_discovered_tool (version_id,tool_name,description,input_schema,risk_level,enabled) VALUES (?,?,?,?,?,0) ON DUPLICATE KEY UPDATE description=VALUES(description),input_schema=VALUES(input_schema),risk_level=VALUES(risk_level)",
                    versionId, required(tool, "name"), text(tool, "description"), JSON.toJSONString(tool.getOrDefault("inputSchema", Map.of())), riskFor(tool));
        }
        transition("mcp_version", versionId, WorkflowTransitionPolicy.Resource.MCP, "DISCOVERED");
        audit("MCP_VERSION", String.valueOf(versionId), "DISCOVER_TOOLS", actor, "TESTER", null, null, "tools=" + tools.size());
    }

    public void scanMcp(long versionId, String actor) {
        requireState("mcp_version", versionId, "DISCOVERED");
        List<Map<String, Object>> tools = jdbc.queryForList("SELECT tool_name,risk_level,input_schema FROM mcp_discovered_tool WHERE version_id=?", versionId);
        long high = tools.stream().filter(t -> "HIGH".equals(t.get("risk_level"))).count();
        String risk = high > 0 ? "HIGH" : "LOW";
        jdbc.update("INSERT INTO mcp_security_scan (version_id,status,risk_level,report_json,scanner_version) VALUES (?,'PASSED',?,?,'v1')",
                versionId, risk, JSON.toJSONString(Map.of("toolCount", tools.size(), "highRiskTools", high)));
        transition("mcp_version", versionId, WorkflowTransitionPolicy.Resource.MCP, "SCANNED");
        audit("MCP_VERSION", String.valueOf(versionId), "SECURITY_SCAN", actor, "SECURITY_REVIEWER", null, null, "risk=" + risk);
    }

    public void completeMcpTests(long versionId, String actor, Map<String, Object> report) {
        requireState("mcp_version", versionId, "SCANNED");
        jdbc.update("INSERT INTO mcp_test_run (version_id,run_type,status,response_json,duration_ms,executed_by) VALUES (?,'SANDBOX','PASSED',?,0,?)",
                versionId, JSON.toJSONString(report == null ? Map.of() : report), actor);
        transition("mcp_version", versionId, WorkflowTransitionPolicy.Resource.MCP, "TESTED");
    }

    public void submitMcpReview(long versionId, String actor) {
        requireRole(actor, "actor"); transition("mcp_version", versionId, WorkflowTransitionPolicy.Resource.MCP, "IN_REVIEW");
        audit("MCP_VERSION", String.valueOf(versionId), "SUBMIT_REVIEW", actor, "DEVELOPER", null, null, null);
    }

    public void reviewMcp(long versionId, String type, String decision, String actor, String comment) {
        Map<String,Object> version = one("SELECT submitted_by,status FROM mcp_version WHERE id=?", versionId);
        if (!"IN_REVIEW".equals(version.get("status"))) throw new IllegalStateException("MCP version is not in review");
        approvals.requireReviewerSeparation(String.valueOf(version.get("submitted_by")), actor, type);
        jdbc.update("INSERT INTO mcp_review (version_id,review_type,decision,reviewer,comment) VALUES (?,?,?,?,?)",
                versionId, type.toUpperCase(), decision.toUpperCase(), actor, comment);
        if (hasApprovals("mcp_review", versionId)) transition("mcp_version", versionId, WorkflowTransitionPolicy.Resource.MCP, "APPROVED");
        audit("MCP_VERSION", String.valueOf(versionId), "REVIEW_" + type.toUpperCase(), actor, type.toUpperCase()+"_REVIEWER", null, null, decision);
    }

    public long releaseMcp(long versionId, String environment, int rollout, String actor) {
        Map<String,Object> version = one("SELECT submitted_by,status FROM mcp_version WHERE id=?", versionId);
        if (!"APPROVED".equals(version.get("status"))) throw new IllegalStateException("MCP version is not approved");
        approvals.requireReleaseAllowed(String.valueOf(version.get("submitted_by")), actor, approvalRows("mcp_review", versionId));
        jdbc.update("INSERT INTO mcp_release (version_id,environment,status,rollout_percent,released_by) VALUES (?,?,'ACTIVE',?,?)",
                versionId, environment, Math.max(1, Math.min(100, rollout)), actor);
        transition("mcp_version", versionId, WorkflowTransitionPolicy.Resource.MCP, "RELEASED");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM mcp_release WHERE version_id=?", Long.class, versionId);
        Map<String,Object> runtime=one("SELECT s.mcp_key,s.name,v.transport_type,v.endpoint_config,v.timeout_seconds FROM mcp_version v JOIN mcp_server s ON s.id=v.server_id WHERE v.id=?",versionId);
        String mcpKey=String.valueOf(runtime.get("mcp_key"));
        jdbc.update("INSERT INTO ai_client_tool_mcp (mcp_id,mcp_name,transport_type,transport_config,request_timeout,status,create_time,update_time) VALUES (?,?,?,?,?,1,NOW(),NOW()) ON DUPLICATE KEY UPDATE mcp_name=VALUES(mcp_name),transport_type=VALUES(transport_type),transport_config=VALUES(transport_config),request_timeout=VALUES(request_timeout),status=1,update_time=NOW()",mcpKey,runtime.get("name"),runtime.get("transport_type"),runtime.get("endpoint_config"),runtime.get("timeout_seconds"));
        // Publishing only makes the version selectable. The MCP client is created
        // after an Agent explicitly binds this released MCP during armory loading.
        audit("MCP_RELEASE", String.valueOf(id), "RELEASE", actor, "RELEASE_MANAGER", null, null, environment);
        return id;
    }

    public void bindMcp(String agentId, long releaseId, String actor, List<String> tools) {
        assertActiveRelease("mcp_release", releaseId);
        String mcpKey=jdbc.queryForObject("SELECT s.mcp_key FROM mcp_release r JOIN mcp_version v ON v.id=r.version_id JOIN mcp_server s ON s.id=v.server_id WHERE r.id=?",String.class,releaseId);
        jdbc.update("INSERT INTO agent_mcp_release_binding (agent_id,release_id,enabled,tool_allowlist_json,bound_by) VALUES (?,?,1,?,?) ON DUPLICATE KEY UPDATE enabled=1,tool_allowlist_json=VALUES(tool_allowlist_json),bound_by=VALUES(bound_by)",
                agentId, releaseId, JSON.toJSONString(tools == null ? List.of() : tools), actor);
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_mcp WHERE agent_id=? AND mcp_id=?",Integer.class,agentId,mcpKey);
        if(exists==null||exists==0)jdbc.update("INSERT INTO ai_agent_mcp (agent_id,mcp_id,status,create_time) VALUES (?,?,1,NOW())",agentId,mcpKey);
        audit("AGENT_MCP_BINDING", agentId + ":" + releaseId, "BIND", actor, "AGENT_ADMIN", null, null, null);
    }

    public List<Map<String,Object>> listMcps() {
        return jdbc.queryForList("SELECT s.id,s.mcp_key,s.name,s.description,s.owner,s.status registration_status,MAX(v.version) latest_version,MAX(v.created_at) updated_at,(SELECT v2.status FROM mcp_version v2 WHERE v2.server_id=s.id ORDER BY v2.created_at DESC,v2.id DESC LIMIT 1) lifecycle_status FROM mcp_server s LEFT JOIN mcp_version v ON v.server_id=s.id GROUP BY s.id,s.mcp_key,s.name,s.description,s.owner,s.status ORDER BY s.updated_at DESC");
    }

    public List<Map<String,Object>> listMcpVersions(long serverId){return jdbc.queryForList("SELECT id,server_id,version,transport_type,status,timeout_seconds,retry_count,concurrency_limit,submitted_by,created_at,(credential_ref<>'') credential_configured FROM mcp_version WHERE server_id=? ORDER BY created_at DESC",serverId);}
    public Map<String,Object> mcpVersionDetail(long versionId){return Map.of("version",one("SELECT id,server_id,version,transport_type,status,timeout_seconds,retry_count,concurrency_limit,submitted_by,created_at,(credential_ref<>'') credential_configured FROM mcp_version WHERE id=?",versionId),"tools",jdbc.queryForList("SELECT id,tool_name,description,risk_level,enabled FROM mcp_discovered_tool WHERE version_id=?",versionId),"tests",jdbc.queryForList("SELECT id,run_type,status,duration_ms,error_message,executed_by,created_at FROM mcp_test_run WHERE version_id=? ORDER BY id DESC",versionId),"reviews",jdbc.queryForList("SELECT review_type,decision,reviewer,comment,created_at FROM mcp_review WHERE version_id=?",versionId),"releases",jdbc.queryForList("SELECT id,environment,status,rollout_percent,released_by,released_at,ended_at FROM mcp_release WHERE version_id=? ORDER BY id DESC",versionId));}

    public long uploadSkill(String key, String name, String description, String version, MultipartFile zip, String actor) throws Exception {
        Path quarantine = Path.of(storageDir, "skills", "quarantine", UUID.randomUUID().toString()).toAbsolutePath().normalize();
        SafeSkillArchiveValidator.ValidationReport report;
        try (InputStream input = zip.getInputStream()) { report = archiveValidator.validateAndExtract(input, quarantine); }
        jdbc.update("INSERT INTO skill_package (skill_key,name,description,owner) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description)", key,name,description,actor);
        long packageId = jdbc.queryForObject("SELECT id FROM skill_package WHERE skill_key=?",Long.class,key);
        String manifest = Files.readString(quarantine.resolve("skill.json"));
        jdbc.update("INSERT INTO skill_version (package_id,version,status,artifact_sha256,manifest_json,submitted_by) VALUES (?,?,'VALIDATED',?,?,?)",
                packageId,version,report.sha256(),manifest,actor);
        long versionId=jdbc.queryForObject("SELECT id FROM skill_version WHERE package_id=? AND version=?",Long.class,packageId,version);
        jdbc.update("INSERT INTO skill_artifact (version_id,stage,storage_path,size_bytes) VALUES (?,'QUARANTINE',?,?)",versionId,quarantine.toString(),report.totalBytes());
        jdbc.update("INSERT INTO skill_validation (version_id,validation_type,status,report_json,validator_version) VALUES (?,'ARCHIVE_AND_MANIFEST','PASSED',?,'v1')",versionId,JSON.toJSONString(report));
        audit("SKILL_VERSION",String.valueOf(versionId),"UPLOAD_VALIDATE",actor,"DEVELOPER",null,null,report.sha256());
        return versionId;
    }

    public void scanSkill(long versionId,String actor) throws Exception {
        requireState("skill_version",versionId,"VALIDATED");
        Map<String,Object> artifact=one("SELECT storage_path FROM skill_artifact WHERE version_id=? ORDER BY id DESC LIMIT 1",versionId);
        String content=Files.readString(Path.of(String.valueOf(artifact.get("storage_path"))).resolve("SKILL.md"));
        List<String> findings=new ArrayList<>();
        for(String pattern:List.of("ignore previous instructions","api_key","secret key","powershell -enc")) if(content.toLowerCase().contains(pattern)) findings.add(pattern);
        String status=findings.isEmpty()?"PASSED":"REVIEW_REQUIRED";
        jdbc.update("INSERT INTO skill_validation (version_id,validation_type,status,report_json,validator_version) VALUES (?,'CONTENT_SECURITY',?,?,'v1')",versionId,status,JSON.toJSONString(findings));
        transition("skill_version",versionId,WorkflowTransitionPolicy.Resource.SKILL,"SCANNED");
        audit("SKILL_VERSION",String.valueOf(versionId),"SECURITY_SCAN",actor,"SECURITY_REVIEWER",null,null,status);
    }

    public void testSkill(long versionId,String actor) throws Exception {
        requireState("skill_version",versionId,"SCANNED");
        Map<String,Object> artifact=one("SELECT storage_path FROM skill_artifact WHERE version_id=? ORDER BY id DESC LIMIT 1",versionId);
        Path root=Path.of(String.valueOf(artifact.get("storage_path")));
        boolean passed=Files.isReadable(root.resolve("SKILL.md"))&&Files.isReadable(root.resolve("skill.json"));
        jdbc.update("INSERT INTO skill_test_run (version_id,status,report_json,executed_by) VALUES (?,?,?,?)",versionId,passed?"PASSED":"FAILED",JSON.toJSONString(Map.of("loadable",passed)),actor);
        if(!passed)throw new IllegalStateException("Skill sandbox load failed");
        transition("skill_version",versionId,WorkflowTransitionPolicy.Resource.SKILL,"TESTED");
    }

    public void submitSkillReview(long versionId,String actor){transition("skill_version",versionId,WorkflowTransitionPolicy.Resource.SKILL,"IN_REVIEW");audit("SKILL_VERSION",String.valueOf(versionId),"SUBMIT_REVIEW",actor,"DEVELOPER",null,null,null);}

    public void reviewSkill(long versionId,String type,String decision,String actor,String comment){
        Map<String,Object> version=one("SELECT submitted_by,status FROM skill_version WHERE id=?",versionId);
        if(!"IN_REVIEW".equals(version.get("status")))throw new IllegalStateException("Skill version is not in review");
        approvals.requireReviewerSeparation(String.valueOf(version.get("submitted_by")),actor,type);
        jdbc.update("INSERT INTO skill_review (version_id,review_type,decision,reviewer,comment) VALUES (?,?,?,?,?)",versionId,type.toUpperCase(),decision.toUpperCase(),actor,comment);
        if(hasApprovals("skill_review",versionId))transition("skill_version",versionId,WorkflowTransitionPolicy.Resource.SKILL,"APPROVED");
        audit("SKILL_VERSION",String.valueOf(versionId),"REVIEW_"+type.toUpperCase(),actor,type.toUpperCase()+"_REVIEWER",null,null,decision);
    }

    public void signSkill(long versionId,String actor){
        requireState("skill_version",versionId,"APPROVED"); transition("skill_version",versionId,WorkflowTransitionPolicy.Resource.SKILL,"SIGNED");
        audit("SKILL_VERSION",String.valueOf(versionId),"SIGN",actor,"RELEASE_MANAGER",null,null,null);
    }

    public long releaseSkill(long versionId,String environment,int rollout,String actor){
        Map<String,Object> version=one("SELECT submitted_by,status,artifact_sha256 FROM skill_version WHERE id=?",versionId);
        if(!"SIGNED".equals(version.get("status")))throw new IllegalStateException("Skill version is not signed");
        approvals.requireReleaseAllowed(String.valueOf(version.get("submitted_by")),actor,approvalRows("skill_review",versionId));
        String signature=sha256(version.get("artifact_sha256")+":"+actor+":"+environment);
        jdbc.update("INSERT INTO skill_release (version_id,environment,status,rollout_percent,signature_value,released_by) VALUES (?,?,'ACTIVE',?,?,?)",versionId,environment,Math.max(1,Math.min(100,rollout)),signature,actor);
        transition("skill_version",versionId,WorkflowTransitionPolicy.Resource.SKILL,"RELEASED");
        publishSkillRuntime(versionId);
        return jdbc.queryForObject("SELECT MAX(id) FROM skill_release WHERE version_id=?",Long.class,versionId);
    }

    public void bindSkill(String agentId,long releaseId,String actor,Map<String,Object> override){assertActiveRelease("skill_release",releaseId);jdbc.update("INSERT INTO agent_skill_release_binding (agent_id,release_id,enabled,config_override_json,bound_by) VALUES (?,?,1,?,?) ON DUPLICATE KEY UPDATE enabled=1,config_override_json=VALUES(config_override_json),bound_by=VALUES(bound_by)",agentId,releaseId,JSON.toJSONString(override==null?Map.of():override),actor);String skillId=jdbc.queryForObject("SELECT CONCAT(p.skill_key,'-',v.version) FROM skill_release r JOIN skill_version v ON v.id=r.version_id JOIN skill_package p ON p.id=v.package_id WHERE r.id=?",String.class,releaseId);Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_skill WHERE agent_id=? AND skill_id=?",Integer.class,agentId,skillId);if(exists==null||exists==0)jdbc.update("INSERT INTO ai_agent_skill (agent_id,skill_id,status,create_time) VALUES (?,?,1,NOW())",agentId,skillId);audit("AGENT_SKILL_BINDING",agentId+":"+releaseId,"BIND",actor,"AGENT_ADMIN",null,null,null);}

    public List<Map<String,Object>> listSkills(){return jdbc.queryForList("SELECT p.id,p.skill_key,p.name,p.description,p.owner,MAX(v.version) latest_version,MAX(v.status) lifecycle_status,MAX(v.created_at) updated_at FROM skill_package p LEFT JOIN skill_version v ON v.package_id=p.id GROUP BY p.id,p.skill_key,p.name,p.description,p.owner ORDER BY updated_at DESC");}
    public void requireReleasedRuntimeBindings(List<String> skillIds, List<String> mcpIds) {
        for (String skillId : skillIds) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM skill_release r JOIN skill_version v ON v.id=r.version_id "
                            + "JOIN skill_package p ON p.id=v.package_id WHERE r.status='ACTIVE' "
                            + "AND CONCAT(p.skill_key,'-',v.version)=?",
                    Integer.class, skillId);
            if ((count == null || count == 0) && !localSkillExists(skillId)) {
                throw new IllegalStateException("Skill must be released before binding: " + skillId);
            }
        }
        for (String mcpId : mcpIds) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mcp_release r JOIN mcp_version v ON v.id=r.version_id "
                            + "JOIN mcp_server s ON s.id=v.server_id WHERE r.status='ACTIVE' AND s.mcp_key=?",
                    Integer.class, mcpId);
            if (count == null || count == 0) {
                throw new IllegalStateException("MCP must be released before binding: " + mcpId);
            }
        }
    }

    public List<Map<String,Object>> listReleasedMcpBindings() {
        return jdbc.queryForList("""
                SELECT s.mcp_key AS mcp_id, s.name AS mcp_name, s.description,
                       v.id AS version_id, v.version, v.transport_type,
                       v.endpoint_config, v.timeout_seconds, r.id AS release_id,
                       r.environment, r.released_at
                FROM mcp_release r
                JOIN mcp_version v ON v.id = r.version_id
                JOIN mcp_server s ON s.id = v.server_id
                WHERE r.status = 'ACTIVE' AND v.status = 'RELEASED'
                ORDER BY r.released_at DESC, r.id DESC
                """);
    }

    private boolean localSkillExists(String skillId) {
        if (skillId == null || skillId.isBlank()) return false;
        Path configuredRoot = Path.of(runtimeSkillsDir).toAbsolutePath().normalize();
        for (Path base : List.of(configuredRoot, Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize())) {
            for (Path current = base; current != null; current = current.getParent()) {
                if (Files.isRegularFile(current.resolve("skills").resolve(skillId.trim()).resolve("SKILL.md"))) {
                    return true;
                }
            }
        }
        return false;
    }
    public List<Map<String,Object>> listSkillVersions(long packageId){return jdbc.queryForList("SELECT id,package_id,version,status,artifact_sha256,submitted_by,created_at FROM skill_version WHERE package_id=? ORDER BY created_at DESC",packageId);}
    public Map<String,Object> skillVersionDetail(long versionId){return Map.of("version",one("SELECT id,package_id,version,status,artifact_sha256,submitted_by,created_at FROM skill_version WHERE id=?",versionId),"validations",jdbc.queryForList("SELECT validation_type,status,report_json,validator_version,created_at FROM skill_validation WHERE version_id=? ORDER BY id DESC",versionId),"tests",jdbc.queryForList("SELECT status,report_json,executed_by,created_at FROM skill_test_run WHERE version_id=? ORDER BY id DESC",versionId),"reviews",jdbc.queryForList("SELECT review_type,decision,reviewer,comment,created_at FROM skill_review WHERE version_id=?",versionId),"releases",jdbc.queryForList("SELECT id,environment,status,rollout_percent,signature_value,released_by,released_at,ended_at FROM skill_release WHERE version_id=? ORDER BY id DESC",versionId));}

    private void transition(String table,long id,WorkflowTransitionPolicy.Resource resource,String target){String from=String.valueOf(one("SELECT status FROM "+table+" WHERE id=?",id).get("status"));transitions.requireAllowed(resource,from,target);jdbc.update("UPDATE "+table+" SET status=? WHERE id=?",target,id);}
    private void requireState(String table,long id,String state){String actual=String.valueOf(one("SELECT status FROM "+table+" WHERE id=?",id).get("status"));if(!state.equals(actual))throw new IllegalStateException("Expected "+state+" but was "+actual);}
    private boolean hasApprovals(String table,long id){List<CapabilityApprovalPolicy.Approval> a=approvalRows(table,id);return a.stream().anyMatch(x->"TEST".equals(x.type())&&"APPROVED".equals(x.decision()))&&a.stream().anyMatch(x->"SECURITY".equals(x.type())&&"APPROVED".equals(x.decision()));}
    private List<CapabilityApprovalPolicy.Approval> approvalRows(String table,long id){return jdbc.query("SELECT review_type,decision,reviewer FROM "+table+" WHERE version_id=?",(rs,n)->new CapabilityApprovalPolicy.Approval(rs.getString(1),rs.getString(2),rs.getString(3)),id);}
    private void assertActiveRelease(String table,long id){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND status='ACTIVE'",Integer.class,id);if(count==null||count!=1)throw new IllegalStateException("Only an active release can be bound");}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new IllegalArgumentException("Resource not found");return rows.get(0);}
    private String riskFor(Map<String,Object> tool){String text=(required(tool,"name")+" "+text(tool,"description")).toLowerCase();return text.matches(".*(delete|shell|execute|write|payment|admin).*" )?"HIGH":"LOW";}
    private String required(Map<String,Object>b,String key){String v=text(b,key);if(v.isBlank())throw new IllegalArgumentException(key+" is required");return v;}
    private String text(Map<String,Object>b,String key){Object v=b.get(key);return v==null?"":String.valueOf(v).trim();}
    private int number(Map<String,Object>b,String key,int fallback){Object v=b.get(key);return v instanceof Number n?n.intValue():fallback;}
    private void requireRole(String value,String field){if(value==null||value.isBlank())throw new IllegalArgumentException(field+" is required");}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void publishSkillRuntime(long versionId){try{Map<String,Object> source=one("SELECT a.storage_path,p.skill_key,v.version FROM skill_artifact a JOIN skill_version v ON v.id=a.version_id JOIN skill_package p ON p.id=v.package_id WHERE v.id=? ORDER BY a.id DESC LIMIT 1",versionId);Path from=Path.of(String.valueOf(source.get("storage_path"))).toAbsolutePath().normalize();Path runtimeRoot=Path.of(runtimeSkillsDir).toAbsolutePath().normalize();Path runtime=runtimeRoot.resolve(source.get("skill_key")+"-"+source.get("version")).normalize();if(!runtime.startsWith(runtimeRoot))throw new IllegalStateException("Invalid Skill runtime path");Files.createDirectories(runtime);try(var paths=Files.walk(from)){paths.forEach(path->{try{Path target=runtime.resolve(from.relativize(path)).normalize();if(!target.startsWith(runtime))throw new IllegalStateException("Invalid runtime path");if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new RuntimeException(e);}});}}catch(Exception e){throw new IllegalStateException("Failed to publish Skill runtime artifact",e);}}
    private void audit(String type,String id,String action,String actor,String role,String reason,String before,String after){jdbc.update("INSERT INTO audit_log (resource_type,resource_id,action,actor,actor_role,reason,before_json,after_json) VALUES (?,?,?,?,?,?,?,?)",type,id,action,actor,role,reason,before,after);}
}
