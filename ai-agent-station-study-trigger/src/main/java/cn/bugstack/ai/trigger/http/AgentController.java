package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.AiModelOptionDTO;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.service.armory.AiClientToolMcpNode;
import cn.bugstack.ai.domain.agent.service.armory.ModelCredentialResolver;
import cn.bugstack.ai.domain.agent.service.skills.SkillFrontmatterParser;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.trigger.service.capability.CapabilityRegistryService;
import cn.bugstack.ai.trigger.service.conversation.ConversationSessionService;
import cn.bugstack.ai.trigger.service.agent.AgentSoulService;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientApiDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientModelDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientToolMcpDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.po.AiAgent;
import cn.bugstack.ai.infrastructure.dao.po.AiClientApi;
import cn.bugstack.ai.infrastructure.dao.po.AiClientToolMcp;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class AgentController {

    @Resource private IAgentRepository agentRepository;
    @Resource private IAiAgentDao aiAgentDao;
    @Resource private IAiClientApiDao aiClientApiDao;
    @Resource private IAiClientModelDao aiClientModelDao;
    @Resource private IAiClientToolMcpDao aiClientToolMcpDao;
    @Resource private IAiSessionDao aiSessionDao;
    @Resource private AiClientToolMcpNode aiClientToolMcpNode;
    @Resource private SkillScannerService skillScannerService;
    @Resource private ReActToolProperties properties;
    @Resource private ModelCredentialResolver modelCredentialResolver;
    @Resource private CapabilityRegistryService capabilityRegistryService;
    @Resource private ConversationSessionService conversationSessionService;
    @Resource private AgentSoulService agentSoulService;
    @Resource(name = "mysqlJdbcTemplate") private JdbcTemplate jdbcTemplate;

    @GetMapping("/models")
    public List<AiModelOptionDTO> listModels() {
        return aiClientModelDao.queryEnabledModels().stream()
                .map(model -> {
                    AiClientApi api = aiClientApiDao.queryByApiId(model.getApiId());
                    boolean configured = api != null
                            && Integer.valueOf(1).equals(api.getStatus())
                            && modelCredentialResolver.isConfigured(api.getApiKey());
                    return AiModelOptionDTO.builder()
                            .modelId(model.getModelId())
                            .modelName(model.getModelName())
                            .modelType(model.getModelType())
                            .providerName(providerName(api))
                            .configured(configured)
                            .build();
                })
                .toList();
    }

    private String providerName(AiClientApi api) {
        if (api == null || api.getBaseUrl() == null) return "Unknown";
        String baseUrl = api.getBaseUrl().toLowerCase();
        if (baseUrl.contains("deepseek")) return "DeepSeek";
        if (baseUrl.contains("sensenova")) return "SenseNova";
        return api.getBaseUrl();
    }

    private void requireConfiguredModel(String modelId) {
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("Agent default model is required");
        var model = aiClientModelDao.queryByModelId(modelId.trim());
        AiClientApi api = model == null ? null : aiClientApiDao.queryByApiId(model.getApiId());
        if (model == null || api == null || !Integer.valueOf(1).equals(api.getStatus()) || !modelCredentialResolver.isConfigured(api.getApiKey())) {
            throw new IllegalArgumentException("Selected model is unavailable or has no configured credential");
        }
    }

    @GetMapping("/agents")
    public List<AiAgent> listAgents() { return aiAgentDao.queryAll(); }

    @PostMapping("/agents")
    public Map<String, Object> createAgent(@RequestBody AiAgent agent) {
        requireConfiguredModel(agent.getModelId());
        agent.setCreateTime(LocalDateTime.now()); agent.setUpdateTime(LocalDateTime.now());
        if (agent.getStatus() == null) agent.setStatus(1);
        int rows = aiAgentDao.insert(agent);
        return Map.of("success", rows > 0, "message", rows > 0 ? "创建成功" : "创建失败");
    }

    @PutMapping("/agents/{agentId}")
    public Map<String, Object> updateAgent(@PathVariable("agentId") String agentId, @RequestBody AiAgent agent) {
        requireConfiguredModel(agent.getModelId());
        if (agent.getAgentId() == null || agent.getAgentId().isBlank()) agent.setAgentId(agentId);
        agent.setUpdateTime(LocalDateTime.now());
        int rows = aiAgentDao.updateByAgentId(agent);
        return Map.of("success", rows > 0, "message", rows > 0 ? "更新成功" : "更新失败");
    }

    @DeleteMapping("/agents/{agentId}")
    public Map<String, Object> deleteAgent(@PathVariable("agentId") String agentId) {
        agentRepository.bindSkills(agentId, List.of()); agentRepository.bindMcps(agentId, List.of()); agentRepository.bindTools(agentId, List.of());
        int rows = aiAgentDao.deleteByAgentId(agentId);
        return Map.of("success", rows > 0, "message", rows > 0 ? "删除成功" : "删除失败");
    }

    @GetMapping("/agents/{agentId}/bindings")
    public Map<String, Object> getBindings(@PathVariable("agentId") String agentId) {
        return Map.of("skillIds", agentRepository.queryBoundSkillIds(agentId),
                "mcpIds", agentRepository.queryBoundMcpIds(agentId),
                "toolIds", agentRepository.queryBoundToolIds(agentId));
    }

    @PutMapping("/agents/{agentId}/bindings")
    public Map<String, Object> updateBindings(@PathVariable("agentId") String agentId, @RequestBody Map<String, List<String>> body) {
        List<String> skillIds = body.getOrDefault("skillIds", List.of());
        List<String> mcpIds = body.getOrDefault("mcpIds", List.of());
        List<String> toolIds = body.getOrDefault("toolIds", List.of());
        capabilityRegistryService.requireReleasedRuntimeBindings(skillIds, mcpIds);
        agentRepository.bindSkills(agentId, skillIds);
        agentRepository.bindMcps(agentId, mcpIds);
        agentRepository.bindTools(agentId, toolIds);
        return Map.of("success", true, "message", "绑定成功");
    }

    @GetMapping("/agent-tools")
    public List<ReActToolAllowlistPolicy.ToolOption> agentTools() {
        return ReActToolAllowlistPolicy.options();
    }

    @GetMapping("/agents/{agentId}/souls")
    public List<Map<String, Object>> listSouls(@PathVariable("agentId") String agentId) {
        return agentSoulService.list(agentId);
    }

    @PostMapping("/agents/{agentId}/souls")
    public Map<String, Object> saveSoul(@PathVariable("agentId") String agentId, @RequestBody Map<String, String> body) {
        return agentSoulService.saveVersion(agentId, body.getOrDefault("content", ""), body.getOrDefault("actor", "local-user"));
    }

    @PostMapping("/agents/{agentId}/souls/{version}/activate")
    public Map<String, Object> activateSoul(@PathVariable("agentId") String agentId, @PathVariable("version") int version, @RequestBody(required = false) Map<String, String> body) {
        return agentSoulService.activate(agentId, version, body == null ? "local-user" : body.getOrDefault("actor", "local-user"));
    }

    @GetMapping("/mcp-tools")
    public List<AiClientToolMcp> listMcpTools() { return aiClientToolMcpDao.queryAll(); }

    @PostMapping("/mcp-tools")
    public Map<String, Object> createMcpTool(@RequestBody AiClientToolMcp tool) {
        if (tool.getMcpId() == null || tool.getMcpId().isBlank()) return Map.of("success", false, "message", "mcpId不能为空");
        tool.setCreateTime(LocalDateTime.now()); tool.setUpdateTime(LocalDateTime.now());
        if (tool.getStatus() == null) tool.setStatus(1);
        if (tool.getRequestTimeout() == null) tool.setRequestTimeout(60);
        int rows = aiClientToolMcpDao.insert(tool);
        if (rows > 0 && tool.getStatus() != null && tool.getStatus() == 1)
            aiClientToolMcpNode.registerMcpSyncClient(tool.getMcpId(), tool.getMcpName(), tool.getTransportType(), tool.getTransportConfig(), tool.getRequestTimeout() != null ? tool.getRequestTimeout() : 60);
        return Map.of("success", rows > 0, "message", rows > 0 ? "创建成功" : "创建失败");
    }

    @PutMapping("/mcp-tools/{id}")
    public Map<String, Object> updateMcpTool(@PathVariable("id") Long id, @RequestBody AiClientToolMcp tool) {
        if (aiClientToolMcpDao.queryById(id) == null) return Map.of("success", false, "message", "ID不存在");
        tool.setId(id); tool.setUpdateTime(LocalDateTime.now());
        return Map.of("success", aiClientToolMcpDao.updateById(tool) > 0);
    }

    @DeleteMapping("/mcp-tools/{id}")
    public Map<String, Object> deleteMcpTool(@PathVariable("id") Long id) {
        return Map.of("success", aiClientToolMcpDao.deleteById(id) > 0);
    }

    @GetMapping("/skills")
    public List<SkillScannerService.SkillInfo> listSkills() {
        return jdbcTemplate.query("""
                        SELECT CONCAT(p.skill_key,'-',v.version) AS skill_id,
                               p.name AS skill_name,
                               p.description AS description
                        FROM skill_release r
                        JOIN skill_version v ON v.id = r.version_id
                        JOIN skill_package p ON p.id = v.package_id
                        WHERE r.status = 'ACTIVE'
                        ORDER BY r.released_at DESC, r.id DESC
                        """,
                (rs, rowNum) -> {
                    String skillId = rs.getString("skill_id");
                    var runtime = skillScannerService.readSkillFromWorkDir(properties.getWorkDir(), skillId);
                    if (runtime != null) return runtime;
                    return SkillScannerService.SkillInfo.builder()
                            .skillId(skillId)
                            .skillName(rs.getString("skill_name"))
                            .description(rs.getString("description"))
                            .content("")
                            .build();
                });
    }

    @GetMapping("/skills/{skillId}")
    public Map<String, Object> getSkill(@PathVariable("skillId") String skillId) {
        var skill = skillScannerService.readSkillFromWorkDir(properties.getWorkDir(), skillId);
        return skill != null ? Map.of("success", true, "skill", skill) : Map.of("success", false, "message", "Skill不存在");
    }

    @PostMapping("/skills/upload")
    public Map<String, Object> uploadSkill(@RequestBody Map<String, String> body) {
        String skillId = body.getOrDefault("skillId", ""), content = body.getOrDefault("content", "");
        String name = body.getOrDefault("name", skillId), desc = body.getOrDefault("description", "");
        if (skillId.isBlank() || content.isBlank()) return Map.of("success", false, "message", "skillId和content不能为空");
        try {
            Path dir = Paths.get(properties.getWorkDir(), "skills", skillId);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), SkillFrontmatterParser.prependFrontmatter(name, desc, content));
            return Map.of("success", true, "message", "上传成功");
        } catch (Exception e) { return Map.of("success", false, "message", "上传失败: " + e.getMessage()); }
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, String> body) {
        String sessionId = cn.bugstack.ai.trigger.service.conversation.ConversationIdPolicy.create();
        String agentId = body.getOrDefault("agentId", "");
        if (agentId.isBlank()) return Map.of("success", false, "message", "agentId不能为空");
        aiSessionDao.insert(AiSession.builder().sessionId(sessionId).agentId(agentId).title(body.getOrDefault("title", "新对话")).messageCount(0).createdAt(LocalDateTime.now()).build());
        return Map.of("success", true, "sessionId", sessionId);
    }

    @GetMapping("/sessions/agent/{agentId}")
    public List<AiSession> listSessions(@PathVariable("agentId") String agentId,
                                         @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return aiSessionDao.queryByAgentId(agentId, limit);
    }

    @PostMapping("/agents/{agentId}/sessions")
    public Map<String, Object> createAgentSession(@PathVariable("agentId") String agentId, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> values = body == null ? Map.of() : body;
        AiSession session = conversationSessionService.create(agentId, values.getOrDefault("title", "新对话"), values.getOrDefault("modelId", ""));
        return Map.of("success", true, "session", session, "sessionId", session.getSessionId());
    }

    @GetMapping("/agents/{agentId}/sessions")
    public List<AiSession> listAgentSessions(@PathVariable("agentId") String agentId, @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return conversationSessionService.list(agentId, limit);
    }

    @GetMapping("/agents/{agentId}/sessions/{sessionId}")
    public Map<String, Object> agentSessionDetail(@PathVariable("agentId") String agentId, @PathVariable("sessionId") String sessionId) {
        return conversationSessionService.detail(agentId, sessionId);
    }

    @PutMapping("/agents/{agentId}/sessions/{sessionId}/title")
    public Map<String, Object> renameAgentSession(@PathVariable("agentId") String agentId,
                                                  @PathVariable("sessionId") String sessionId,
                                                  @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> values = body == null ? Map.of() : body;
        AiSession session = conversationSessionService.rename(agentId, sessionId, values.getOrDefault("title", ""));
        return Map.of("success", true, "session", session);
    }

    @DeleteMapping("/agents/{agentId}/sessions/{sessionId}")
    public Map<String, Object> deleteAgentSession(@PathVariable("agentId") String agentId,
                                                  @PathVariable("sessionId") String sessionId) {
        AiSession session = conversationSessionService.delete(agentId, sessionId);
        return Map.of("success", true, "session", session);
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> getSession(@PathVariable("sessionId") String sessionId) {
        AiSession s = aiSessionDao.queryBySessionId(sessionId);
        return s != null ? Map.of("success", true, "session", s) : Map.of("success", false, "message", "会话不存在");
    }

    @PutMapping("/sessions/{sessionId}/title")
    public Map<String, Object> updateSessionTitle(@PathVariable("sessionId") String sessionId, @RequestBody Map<String, String> body) {
        aiSessionDao.updateTitle(sessionId, body.getOrDefault("title", ""));
        return Map.of("success", true);
    }
}
