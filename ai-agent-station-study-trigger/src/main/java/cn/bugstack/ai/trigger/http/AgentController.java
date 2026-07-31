package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.AiClientApiOptionDTO;
import cn.bugstack.ai.api.dto.AiModelOptionDTO;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.armory.AiClientToolMcpNode;
import cn.bugstack.ai.domain.agent.service.armory.ModelCredentialResolver;
import cn.bugstack.ai.domain.agent.service.runtime.AgentRuntimeBindingService;
import cn.bugstack.ai.domain.agent.service.workspace.AgentWorkspaceService;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActToolAllowlistPolicy;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.trigger.service.capability.CapabilityRegistryService;
import cn.bugstack.ai.trigger.service.conversation.ConversationSessionService;
import cn.bugstack.ai.trigger.service.agent.AgentSoulService;
import cn.bugstack.ai.trigger.service.model.ChatModelBeanRegistrar;
import cn.bugstack.ai.trigger.service.skill.SkillCatalogService;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientApiDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientModelDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientToolMcpDao;
import cn.bugstack.ai.infrastructure.dao.IAiSessionDao;
import cn.bugstack.ai.infrastructure.dao.po.AiAgent;
import cn.bugstack.ai.infrastructure.dao.po.AiClientApi;
import cn.bugstack.ai.infrastructure.dao.po.AiClientModel;
import cn.bugstack.ai.infrastructure.dao.po.AiClientToolMcp;
import cn.bugstack.ai.infrastructure.dao.po.AiSession;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.firstNonBlank;

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
    @Resource private ChatModelBeanRegistrar chatModelBeanRegistrar;
    @Resource private CapabilityRegistryService capabilityRegistryService;
    @Resource private ConversationSessionService conversationSessionService;
    @Resource private AgentSoulService agentSoulService;
    @Resource private SkillCatalogService skillCatalogService;
    @Resource private AgentWorkspaceService agentWorkspaceService;
    @Resource private ReActToolAllowlistPolicy reActToolAllowlistPolicy;
    @Resource private AgentRuntimeBindingService agentRuntimeBindingService;

    @Data
    public static class ModelBindingRequest {
        private String modelId;
        private String modelName;
        private String modelType;
        private Integer modelStatus;
        private String apiId;
        private String modelUrl;
        private String baseUrl;
        private String apiKey;
        private String completionsPath;
        private String embeddingsPath;
        private Integer apiStatus;
    }

    @GetMapping("/models")
    public List<AiModelOptionDTO> listModels() {
        return aiClientModelDao.queryAll().stream()
                .map(model -> {
                    AiClientApi api = aiClientApiDao.queryByApiId(model.getApiId());
                    boolean configured = api != null
                            && Integer.valueOf(1).equals(api.getStatus())
                            && modelCredentialResolver.isConfigured(api.getApiKey());
                    return AiModelOptionDTO.builder()
                            .modelId(model.getModelId())
                            .apiId(model.getApiId())
                            .modelName(model.getModelName())
                            .modelType(model.getModelType())
                            .status(model.getStatus())
                            .providerName(providerName(api))
                            .configured(configured)
                            .build();
                })
                .toList();
    }

    @GetMapping("/client-apis")
    public List<AiClientApiOptionDTO> listClientApis() {
        return aiClientApiDao.queryAll().stream()
                .map(api -> AiClientApiOptionDTO.builder()
                        .apiId(api.getApiId())
                        .baseUrl(api.getBaseUrl())
                        .status(api.getStatus())
                        .build())
                .toList();
    }

    @GetMapping("/client-apis/{apiId}")
    public Map<String, Object> getClientApi(@PathVariable("apiId") String apiId) {
        AiClientApi api = aiClientApiDao.queryByApiId(apiId);
        return api == null ? Map.of("success", false, "message", "API not found") : Map.of("success", true, "api", api);
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

    private void refreshRuntimeModel(AiClientApi api, AiClientModel model) {
        // 委托统一装配器：构建 OpenAiApi + OpenAiChatModel（纯模型，不带 toolCallbacks）并注册 Bean。
        // 执行策略（Chat / ReAct）各自用 ChatClient 重新组装工具链，模型本身保持轻量。
        chatModelBeanRegistrar.register(api, model);
    }

    private void refreshRuntimeModelsByApiId(AiClientApi api) {
        List<AiClientModel> linkedModels = aiClientModelDao.queryByApiId(api.getApiId());
        for (AiClientModel linkedModel : linkedModels) {
            chatModelBeanRegistrar.register(api, linkedModel);
        }
    }

    private String normalizeBaseUrl(ModelBindingRequest request) {
        String raw = firstNonBlank(request.getModelUrl(), request.getBaseUrl(), "").trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        String path = normalizeCompletionsPath(request);
        // 若用户填的是完整 endpoint（含 path 后缀），剥掉后缀即得 baseUrl，保证 baseUrl + path == 原始输入
        if (!path.isBlank() && raw.endsWith(path)) {
            return raw.substring(0, raw.length() - path.length());
        }
        // path 以 /v1 开头而 raw 恰以 /v1 结尾时存在重叠，剥掉 raw 的 /v1 避免 /v1/v1
        if (path.startsWith("/v1") && raw.endsWith("/v1")) {
            return raw.substring(0, raw.length() - 3);
        }
        return raw;
    }

    private String normalizeCompletionsPath(ModelBindingRequest request) {
        String raw = firstNonBlank(request.getModelUrl(), request.getBaseUrl(), "").trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.endsWith("/v1/chat/completions")) return "/v1/chat/completions";
        if (raw.endsWith("/chat/completions")) return "/chat/completions";
        if (raw.endsWith("/v1")) return "/v1/chat/completions";
        String configured = firstNonBlank(request.getCompletionsPath(), "").trim();
        if (!configured.isBlank()) return configured.startsWith("/") ? configured : "/" + configured;
        return "/v1/chat/completions";
    }

    private String normalizeModelId(String modelId, String modelName) {
        String value = firstNonBlank(modelId, modelName, "model").trim().toLowerCase();
        value = value.replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return value.isBlank() ? "model" : value;
    }

    private String normalizeApiId(String apiId, String modelId) {
        return firstNonBlank(apiId, "api-" + modelId).trim();
    }

    private AiClientApi upsertClientApi(String apiId, String baseUrl, String apiKey,
                                        String completionsPath, String embeddingsPath,
                                        Integer status, LocalDateTime now) {
        AiClientApi existed = aiClientApiDao.queryByApiId(apiId);
        if (existed == null) {
            AiClientApi created = AiClientApi.builder()
                    .apiId(apiId)
                    .baseUrl(baseUrl)
                    .apiKey(apiKey == null ? "" : apiKey.trim())
                    .completionsPath(firstNonBlank(completionsPath, "/chat/completions"))
                    .embeddingsPath(firstNonBlank(embeddingsPath, "/embeddings"))
                    .status(status == null ? 1 : status)
                    .createTime(now)
                    .updateTime(now)
                    .build();
            aiClientApiDao.insert(created);
            return created;
        }
        existed.setBaseUrl(baseUrl);
        if (apiKey != null) existed.setApiKey(apiKey.trim());
        existed.setCompletionsPath(firstNonBlank(completionsPath, existed.getCompletionsPath(), "/chat/completions"));
        existed.setEmbeddingsPath(firstNonBlank(embeddingsPath, existed.getEmbeddingsPath(), "/embeddings"));
        existed.setStatus(status == null ? (existed.getStatus() == null ? 1 : existed.getStatus()) : status);
        existed.setUpdateTime(now);
        aiClientApiDao.updateByApiId(existed);
        return existed;
    }

    private AiClientModel upsertClientModel(String modelId, String apiId, String modelName,
                                            String modelType, Integer status, LocalDateTime now) {
        AiClientModel existed = aiClientModelDao.queryByModelId(modelId);
        if (existed == null) {
            AiClientModel created = AiClientModel.builder()
                    .modelId(modelId)
                    .apiId(apiId)
                    .modelName(modelName.trim())
                    .modelType(firstNonBlank(modelType, "openai"))
                    .status(status == null ? 1 : status)
                    .createTime(now)
                    .updateTime(now)
                    .build();
            aiClientModelDao.insert(created);
            return created;
        }
        existed.setApiId(apiId);
        existed.setModelName(modelName.trim());
        existed.setModelType(firstNonBlank(modelType, existed.getModelType(), "openai"));
        existed.setStatus(status == null ? (existed.getStatus() == null ? 1 : existed.getStatus()) : status);
        existed.setUpdateTime(now);
        aiClientModelDao.updateByModelId(existed);
        return existed;
    }

    @PostMapping("/client-model-bindings")
    @Transactional
    public Map<String, Object> saveClientModelBinding(@RequestBody ModelBindingRequest request) {
        if (request == null) return Map.of("success", false, "message", "\u8bf7\u6c42\u4f53\u4e0d\u80fd\u4e3a\u7a7a");
        if (request.getModelName() == null || request.getModelName().isBlank()) return Map.of("success", false, "message", "\u6a21\u578b\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a");
        String baseUrl = normalizeBaseUrl(request);
        if (baseUrl.isBlank()) return Map.of("success", false, "message", "\u6a21\u578b URL \u4e0d\u80fd\u4e3a\u7a7a");
        String modelId = normalizeModelId(request.getModelId(), request.getModelName());
        String apiId = normalizeApiId(request.getApiId(), modelId);

        LocalDateTime now = LocalDateTime.now();
        AiClientApi api = upsertClientApi(apiId, baseUrl, request.getApiKey(), normalizeCompletionsPath(request), request.getEmbeddingsPath(), request.getApiStatus(), now);
        AiClientModel model = upsertClientModel(modelId, apiId, request.getModelName(), request.getModelType(), request.getModelStatus(), now);
        refreshRuntimeModel(api, model);
        return Map.of("success", true, "modelId", model.getModelId(), "apiId", api.getApiId(), "message", "\u6a21\u578b\u4fdd\u5b58\u6210\u529f");
    }

    @PutMapping("/client-models/{modelId}")
    public Map<String, Object> updateClientModel(@PathVariable("modelId") String modelId, @RequestBody ModelBindingRequest request) {
        if (request == null) return Map.of("success", false, "message", "\u8bf7\u6c42\u4f53\u4e0d\u80fd\u4e3a\u7a7a");
        AiClientModel existed = aiClientModelDao.queryByModelId(modelId);
        if (existed == null) return Map.of("success", false, "message", "模型不存在");

        String apiId = firstNonBlank(request.getApiId(), existed.getApiId(), modelId);
        AiClientApi api = aiClientApiDao.queryByApiId(apiId);
        String normalizedBaseUrl = normalizeBaseUrl(request);
        if (api == null) {
            api = AiClientApi.builder()
                    .apiId(apiId)
                    .baseUrl(normalizedBaseUrl)
                    .apiKey(firstNonBlank(request.getApiKey(), ""))
                    .completionsPath(normalizeCompletionsPath(request))
                    .embeddingsPath("/embeddings")
                    .status(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            aiClientApiDao.insert(api);
        } else {
            if (!normalizedBaseUrl.isBlank()) api.setBaseUrl(normalizedBaseUrl);
            if (request.getApiKey() != null) api.setApiKey(request.getApiKey().trim());
            api.setCompletionsPath(normalizeCompletionsPath(request));
            api.setUpdateTime(LocalDateTime.now());
            aiClientApiDao.updateByApiId(api);
        }

        AiClientModel toSave = AiClientModel.builder()
                .modelId(modelId.trim())
                .apiId(apiId)
                .modelName(firstNonBlank(request.getModelName(), existed.getModelName()))
                .modelType(firstNonBlank(request.getModelType(), existed.getModelType(), "openai"))
                .status(request.getModelStatus() == null ? (existed.getStatus() == null ? 1 : existed.getStatus()) : request.getModelStatus())
                .createTime(existed.getCreateTime())
                .updateTime(LocalDateTime.now())
                .build();
        int rows = aiClientModelDao.updateByModelId(toSave);
        refreshRuntimeModel(api, toSave);
        return Map.of("success", rows > 0, "message", rows > 0 ? "模型已更新" : "模型更新失败");
    }

    @DeleteMapping("/client-models/{modelId}")
    public Map<String, Object> deleteClientModel(@PathVariable("modelId") String modelId) {
        AiClientModel existed = aiClientModelDao.queryByModelId(modelId);
        String apiId = existed == null ? null : existed.getApiId();
        int rows = aiClientModelDao.deleteByModelId(modelId);
        if (rows > 0) {
            // 注销运行时 Bean，避免残留已删模型被后续对话取到
            chatModelBeanRegistrar.unregister(modelId, apiId);
        }
        return Map.of("success", rows > 0, "message", rows > 0 ? "模型已删除" : "模型删除失败");
    }

    @PutMapping("/client-apis/{apiId}")
    public Map<String, Object> updateClientApi(@PathVariable("apiId") String apiId, @RequestBody AiClientApi api) {
        if (aiClientApiDao.queryByApiId(apiId) == null) return Map.of("success", false, "message", "API not found");
        if (api.getBaseUrl() == null || api.getBaseUrl().isBlank()) return Map.of("success", false, "message", "baseUrl is required");
        api.setApiId(apiId.trim());
        api.setStatus(api.getStatus() == null ? 1 : api.getStatus());
        api.setUpdateTime(LocalDateTime.now());
        int rows = aiClientApiDao.updateByApiId(api);
        refreshRuntimeModelsByApiId(api);
        return Map.of("success", rows > 0, "message", rows > 0 ? "模型已更新" : "模型更新失败");
    }
    @GetMapping("/agents")
    public List<AiAgent> listAgents() { return aiAgentDao.queryAll(); }

    @PostMapping("/agents")
    public Map<String, Object> createAgent(@RequestBody AiAgent agent) {
        requireConfiguredModel(agent.getModelId());
        agent.setCreateTime(LocalDateTime.now()); agent.setUpdateTime(LocalDateTime.now());
        if (agent.getStatus() == null) agent.setStatus(1);
        int rows = aiAgentDao.insert(agent);
        return Map.of("success", rows > 0, "message", rows > 0 ? "模型已更新" : "模型更新失败");
    }

    @PutMapping("/agents/{agentId}")
    public Map<String, Object> updateAgent(@PathVariable("agentId") String agentId, @RequestBody AiAgent agent) {
        AiAgent existing = aiAgentDao.queryByAgentId(agentId);
        if (existing == null) return Map.of("success", false, "message", "Agent not found");
        String requestedModelId = agent.getModelId();
        if (requestedModelId == null || requestedModelId.isBlank()) {
            requestedModelId = existing.getModelId();
        }
        if (requestedModelId == null || requestedModelId.isBlank()) {
            return Map.of("success", false, "message", "modelId is required");
        }
        if (!requestedModelId.equals(existing.getModelId())) {
            requireConfiguredModel(requestedModelId);
        }
        if (agent.getAgentId() == null || agent.getAgentId().isBlank()) agent.setAgentId(agentId);
        agent.setModelId(requestedModelId);
        agent.setUpdateTime(LocalDateTime.now());
        int rows = aiAgentDao.updateByAgentId(agent);
        return Map.of("success", rows > 0, "message", rows > 0 ? "模型已更新" : "模型更新失败");
    }
    @DeleteMapping("/agents/{agentId}")
    public Map<String, Object> deleteAgent(@PathVariable("agentId") String agentId) {
        agentRepository.bindSkills(agentId, List.of()); agentRepository.bindMcps(agentId, List.of()); agentRepository.bindTools(agentId, List.of());
        int rows = aiAgentDao.deleteByAgentId(agentId);
        return Map.of("success", rows > 0, "message", rows > 0 ? "模型已更新" : "模型更新失败");
    }

    @GetMapping("/agents/{agentId}/bindings")
    public Map<String, Object> getBindings(@PathVariable("agentId") String agentId) {
        return Map.of("skillIds", agentRepository.queryBoundSkillIds(agentId),
                "mcpIds", agentRepository.queryBoundMcpIds(agentId),
                "toolIds", agentRepository.queryBoundToolIds(agentId));
    }

    @GetMapping("/agents/{agentId}/bindings/detail")
    public Map<String, Object> getBindingDetails(@PathVariable("agentId") String agentId) {
        AgentRuntimeBindingService.AgentRuntimeBindings bindings;
        try {
            bindings = agentRuntimeBindingService.assemble(agentId, properties.getWorkDir(), false);
        } catch (IllegalArgumentException exception) {
            return Map.of("success", false, "message", "Agent not found");
        }
        List<String> skillIds = bindings.getSkillIds();
        List<String> mcpIds = bindings.getMcpIds();
        List<String> toolIds = bindings.getExplicitToolIds();
        List<String> effectiveToolIds = bindings.getEffectiveToolIds();
        Path workspace = bindings.getWorkspace();
        List<Map<String, Object>> skills = skillIds.stream()
                .map(skillId -> {
                    var metadata = bindings.getSkillMetadataById().get(skillId);
                    boolean runtimeAvailable = metadata != null;
                    return Map.<String, Object>of(
                            "skillId", skillId,
                            "skillName", metadata == null ? skillId : firstNonBlank(metadata.getSkillName(), skillId),
                            "description", metadata == null ? "" : firstNonBlank(metadata.getDescription(), ""),
                            "runtimePath", ".ma/skills/" + skillId + "/SKILL.md",
                            "runtimeAvailable", runtimeAvailable,
                            "runtimeStatus", runtimeAvailable ? "AVAILABLE" : "UNAVAILABLE",
                            "runtimeStatusText", runtimeAvailable ? "已同步到运行时" : "运行时未发现该 Skill",
                            "bound", true
                    );
                }).toList();
        Map<String, ReActToolAllowlistPolicy.ToolOption> toolOptionMap = ReActToolAllowlistPolicy.options().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReActToolAllowlistPolicy.ToolOption::toolId,
                        option -> option,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new));
        List<Map<String, Object>> tools = toolIds.stream()
                .map(toolOptionMap::get)
                .filter(java.util.Objects::nonNull)
                .map(option -> Map.<String, Object>of(
                        "toolId", option.toolId(),
                        "name", option.name(),
                        "description", option.description(),
                        "riskLevel", option.riskLevel(),
                        "bound", true
                )).toList();
        List<Map<String, Object>> effectiveTools = effectiveToolIds.stream()
                .map(toolOptionMap::get)
                .filter(java.util.Objects::nonNull)
                .map(option -> Map.<String, Object>of(
                        "toolId", option.toolId(),
                        "name", option.name(),
                        "description", option.description(),
                        "riskLevel", option.riskLevel(),
                        "source", impliedToolSource(option.toolId(), toolIds, skillIds, mcpIds)
                )).toList();
        Map<String, AiClientToolMcpVO> boundMcpMap = bindings.getMcpTools().stream()
                .filter(java.util.Objects::nonNull)
                .filter(mcp -> mcp.getMcpId() != null && !mcp.getMcpId().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        AiClientToolMcpVO::getMcpId,
                        mcp -> mcp,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new
                ));
        List<Map<String, Object>> mcps = mcpIds.stream()
                .map(mcpId -> {
                    AiClientToolMcpVO mcp = boundMcpMap.get(mcpId);
                    boolean runtimeAvailable = mcp != null;
                    return Map.<String, Object>of(
                            "mcpId", firstNonBlank(mcpId, ""),
                            "mcpName", mcp == null ? firstNonBlank(mcpId, "") : firstNonBlank(mcp.getMcpName(), firstNonBlank(mcp.getMcpId(), "")),
                            "description", "",
                            "transportType", mcp == null ? "" : firstNonBlank(mcp.getTransportType(), ""),
                            "runtimeAvailable", runtimeAvailable,
                            "runtimeStatus", runtimeAvailable ? "AVAILABLE" : "UNAVAILABLE",
                            "runtimeStatusText", runtimeAvailable ? "已同步到运行时" : "运行时未发现该 MCP",
                            "bound", true
                    );
                }).toList();
        return Map.of(
                "success", true,
                "agentId", agentId,
                "workspace", workspace.toString(),
                "skills", skills,
                "mcps", mcps,
                "tools", tools,
                "effectiveToolIds", effectiveToolIds,
                "effectiveTools", effectiveTools
        );
    }

    @PutMapping("/agents/{agentId}/bindings")
    public Map<String, Object> updateBindings(@PathVariable("agentId") String agentId, @RequestBody Map<String, List<String>> body) {
        List<String> skillIds = body.getOrDefault("skillIds", List.of());
        List<String> mcpIds = body.getOrDefault("mcpIds", List.of());
        List<String> toolIds = reActToolAllowlistPolicy.resolve(body.getOrDefault("toolIds", List.of()));
        try {
            capabilityRegistryService.requireReleasedRuntimeBindings(skillIds, mcpIds);
            AiAgent agent = aiAgentDao.queryByAgentId(agentId);
            if (agent == null) {
                return Map.of("success", false, "message", "Agent not found");
            }
            Path workspace = agentWorkspaceService.syncSkills(agentId, agent.getWorkDir(), properties.getWorkDir(), skillIds);
            agentRepository.bindSkills(agentId, skillIds);
            agentRepository.bindMcps(agentId, mcpIds);
            agentRepository.bindTools(agentId, toolIds);
            return Map.of(
                    "success", true,
                    "message", "bindings saved",
                    "agentId", agentId,
                    "workspace", workspace.toString(),
                    "skillIds", skillIds,
                    "mcpIds", mcpIds,
                    "toolIds", toolIds
            );
        } catch (IllegalStateException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @GetMapping("/agent-tools")
    public List<ReActToolAllowlistPolicy.ToolOption> agentTools() {
        return ReActToolAllowlistPolicy.options();
    }

    private List<String> effectiveToolIds(List<String> boundToolIds, List<String> skillIds, List<String> mcpIds) {
        return agentRuntimeBindingService.resolveEffectiveToolIds(
                reActToolAllowlistPolicy.resolve(boundToolIds), skillIds, mcpIds);
    }

    private String impliedToolSource(String toolId, List<String> boundToolIds, List<String> skillIds, List<String> mcpIds) {
        String normalized = firstNonBlank(toolId, "").trim().toLowerCase();
        if (reActToolAllowlistPolicy.resolve(boundToolIds).contains(normalized)) {
            return "agent_binding";
        }
        if (ReActToolAllowlistPolicy.READ_FILE.equals(normalized) && skillIds != null && !skillIds.isEmpty()) {
            return "skill_binding";
        }
        if (ReActToolAllowlistPolicy.CALL_MCP_TOOL.equals(normalized) && mcpIds != null && !mcpIds.isEmpty()) {
            return "mcp_binding";
        }
        if (ReActToolAllowlistPolicy.DISPATCH_SUBAGENTS.equals(normalized)
                && reActToolAllowlistPolicy.resolve(boundToolIds).contains(ReActToolAllowlistPolicy.TASK)) {
            return "task_cascade";
        }
        return "agent_binding";
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
        if (tool.getMcpId() == null || tool.getMcpId().isBlank()) return Map.of("success", false, "message", "mcpId is required");
        tool.setCreateTime(LocalDateTime.now()); tool.setUpdateTime(LocalDateTime.now());
        if (tool.getStatus() == null) tool.setStatus(1);
        if (tool.getRequestTimeout() == null) tool.setRequestTimeout(60);
        int rows = aiClientToolMcpDao.insert(tool);
        if (rows > 0 && tool.getStatus() != null && tool.getStatus() == 1)
            aiClientToolMcpNode.registerMcpSyncClient(tool.getMcpId(), tool.getMcpName(), tool.getTransportType(), tool.getTransportConfig(), tool.getRequestTimeout() != null ? tool.getRequestTimeout() : 60);
        return Map.of("success", rows > 0, "message", rows > 0 ? "模型已更新" : "模型更新失败");
    }

    @PutMapping("/mcp-tools/{id}")
    public Map<String, Object> updateMcpTool(@PathVariable("id") Long id, @RequestBody AiClientToolMcp tool) {
        if (aiClientToolMcpDao.queryById(id) == null) return Map.of("success", false, "message", "ID not found");
        tool.setId(id); tool.setUpdateTime(LocalDateTime.now());
        return Map.of("success", aiClientToolMcpDao.updateById(tool) > 0);
    }

    @DeleteMapping("/mcp-tools/{id}")
    public Map<String, Object> deleteMcpTool(@PathVariable("id") Long id) {
        return Map.of("success", aiClientToolMcpDao.deleteById(id) > 0);
    }

    @GetMapping("/skills")
    public List<SkillScannerService.SkillInfo> listSkills() { return skillCatalogService.listSkills(); }

    @GetMapping("/skills/{skillId}")
    public Map<String, Object> getSkill(@PathVariable("skillId") String skillId,
                                        @RequestParam(value = "agentId", required = false) String agentId) {
        boolean boundToAgent = false;
        if (agentId != null && !agentId.isBlank()) {
            boundToAgent = agentRepository.queryBoundSkillIds(agentId).contains(skillId);
            var skill = boundToAgent ? readRuntimeSkill(agentId, skillId) : null;
            if (skill == null) {
                skill = skillScannerService.readSkillFromWorkDir(properties.getWorkDir(), skillId);
            }
            return skill != null
                    ? Map.of("success", true, "skill", skill, "boundToAgent", boundToAgent, "runtimeAvailable", boundToAgent)
                    : Map.of("success", false, "message", "Skill not found in Agent runtime workspace");
        }
        var skill = readRuntimeSkill(agentId, skillId);
        if (skill == null) {
            skill = skillScannerService.readSkillFromWorkDir(properties.getWorkDir(), skillId);
        }
        return skill != null ? Map.of("success", true, "skill", skill) : Map.of("success", false, "message", "Skill not found");
    }

    private SkillScannerService.SkillInfo readRuntimeSkill(String agentId, String skillId) {
        if (skillId == null || skillId.isBlank() || agentId == null || agentId.isBlank()) return null;
        AiAgent agent = aiAgentDao.queryByAgentId(agentId);
        if (agent == null) return null;
        Path workspace = agentWorkspaceService.resolveWorkDir(agentId, agent.getWorkDir(), properties.getWorkDir());
        return skillScannerService.readSkillFromWorkDir(workspace.toString(), skillId);
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, String> body) {
        String sessionId = cn.bugstack.ai.trigger.service.conversation.ConversationIdPolicy.create();
        String agentId = body.getOrDefault("agentId", "");
        if (agentId.isBlank()) return Map.of("success", false, "message", "agentId is required");
        aiSessionDao.insert(AiSession.builder().sessionId(sessionId).agentId(agentId).title(body.getOrDefault("title", "New chat")).messageCount(0).createdAt(LocalDateTime.now()).build());
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
        AiSession session = conversationSessionService.create(agentId, values.getOrDefault("title", "New chat"), values.getOrDefault("modelId", ""));
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
        return s != null ? Map.of("success", true, "session", s) : Map.of("success", false, "message", "Conversation not found");
    }

    @PutMapping("/sessions/{sessionId}/title")
    public Map<String, Object> updateSessionTitle(@PathVariable("sessionId") String sessionId, @RequestBody Map<String, String> body) {
        aiSessionDao.updateTitle(sessionId, body.getOrDefault("title", ""));
        return Map.of("success", true);
    }
}
