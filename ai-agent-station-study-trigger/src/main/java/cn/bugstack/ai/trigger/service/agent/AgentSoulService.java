package cn.bugstack.ai.trigger.service.agent;

import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@Service
public class AgentSoulService {
    @Resource private IAiAgentDao agentDao;
    @Resource(name = "mysqlJdbcTemplate") private JdbcTemplate jdbc;

    public List<Map<String, Object>> list(String agentId) {
        requireAgent(agentId);
        return jdbc.queryForList("SELECT id,agent_id,version,status,content_sha256,created_by,created_at,activated_at FROM agent_soul_version WHERE agent_id=? ORDER BY version DESC", agentId);
    }

    @Transactional
    public Map<String, Object> saveVersion(String agentId, String content, String actor) {
        requireAgent(agentId);
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Soul Markdown cannot be empty");
        Integer current = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM agent_soul_version WHERE agent_id=?", Integer.class, agentId);
        int version = (current == null ? 0 : current) + 1;
        jdbc.update("INSERT INTO agent_soul_version(agent_id,version,content,content_sha256,status,created_by) VALUES (?,?,?,?, 'DRAFT',?)",
                agentId, version, content.trim(), sha256(content.trim()), safeActor(actor));
        return Map.of("agentId", agentId, "version", version, "status", "DRAFT");
    }

    @Transactional
    public Map<String, Object> activate(String agentId, int version, String actor) {
        requireAgent(agentId);
        List<Map<String, Object>> versions = jdbc.queryForList("SELECT content FROM agent_soul_version WHERE agent_id=? AND version=?", agentId, version);
        if (versions.isEmpty()) throw new IllegalArgumentException("Soul version does not belong to this Agent");
        String content = String.valueOf(versions.get(0).get("content"));
        jdbc.update("UPDATE agent_soul_version SET status='ARCHIVED' WHERE agent_id=? AND status='ACTIVE'", agentId);
        jdbc.update("UPDATE agent_soul_version SET status='ACTIVE', activated_at=NOW() WHERE agent_id=? AND version=?", agentId, version);
        jdbc.update("UPDATE ai_agent SET system_prompt=?, update_time=NOW() WHERE agent_id=?", content, agentId);
        return Map.of("agentId", agentId, "version", version, "status", "ACTIVE", "actor", safeActor(actor));
    }

    private void requireAgent(String agentId) { if (agentId == null || agentId.isBlank() || agentDao.queryByAgentId(agentId) == null) throw new IllegalArgumentException("Agent does not exist"); }
    private static String safeActor(String actor) { return actor == null || actor.isBlank() ? "local-user" : actor.trim(); }
    private static String sha256(String content) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte value : digest) out.append(String.format("%02x", value)); return out.toString(); }
        catch (Exception exception) { throw new IllegalStateException("Unable to hash Soul Markdown", exception); }
    }
}
