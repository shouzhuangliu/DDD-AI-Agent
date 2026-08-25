package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import cn.bugstack.ai.domain.agent.service.tools.mcp.StreamableHttpClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.net.http.HttpClient;

/**
 * MCP客户端配置节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/5 12:48
 */
@Slf4j
@Service
public class AiClientToolMcpNode extends AbstractArmorySupport {
    @Resource
    private AiClientModelNode aiClientModelNode;

    /**
     * Agent 装配通常只会加载被 client 引用的 MCP。应用重启后补偿注册所有启用配置，
     * 这样数据库中已发布但暂未绑定到 client 的 MCP 也能被 Agent 编辑页使用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerEnabledMcpsAtStartup() {
        List<AiClientToolMcpVO> enabledMcps;
        try {
            enabledMcps = repository.queryEnabledMcpTools();
        } catch (Exception e) {
            log.error("启动恢复 MCP 配置失败", e);
            return;
        }
        if (enabledMcps == null || enabledMcps.isEmpty()) {
            log.info("启动恢复 MCP：没有启用的 MCP 配置");
            return;
        }
        for (AiClientToolMcpVO mcp : enabledMcps) {
            if (mcp == null || StringUtils.isBlank(mcp.getMcpId())) {
                continue;
            }
            registerMcpSyncClient(
                    mcp.getMcpId(),
                    mcp.getMcpName(),
                    mcp.getTransportType(),
                    mcp.getTransportConfig(),
                    mcp.getRequestTimeout() == null ? 60 : mcp.getRequestTimeout());
        }
    }
    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Tool MCP 工具配置{}", JSON.toJSONString(requestParameter));
        List<AiClientToolMcpVO> aiClientToolMcpList = dynamicContext.getValue(dataName());

        if (aiClientToolMcpList == null || aiClientToolMcpList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client tool mcp");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientToolMcpVO mcpVO : aiClientToolMcpList) {
            // 创建 MCP 服务
            McpSyncClient mcpSyncClient = createMcpSyncClient(mcpVO);

            // 注册 MCP 对象
            registerBean(beanName(mcpVO.getMcpId()), McpSyncClient.class, mcpSyncClient);
        }

        return router(requestParameter, dynamicContext);
    }

    public McpSyncClient createMcpSyncClient(AiClientToolMcpVO aiClientToolMcpVO) {
        String transportType = aiClientToolMcpVO.getTransportType();
        //两种方式
        switch (transportType){
            case "sse" ->{
                AiClientToolMcpVO.TransportConfigSse transportConfigSse
                        = aiClientToolMcpVO.getTransportConfigSse();
                // 示例：不要把真实凭据写入 URL；请通过受保护的 MCP 凭据配置注入 API Key。
                // http://127.0.0.1:9999/sse?apikey=<MCP_API_KEY>
                String originalBaseUri = transportConfigSse.getBaseUri();
                String baseUri;
                String sseEndpoint;
                int queryParamStartIndex = originalBaseUri.indexOf("sse");
                if (queryParamStartIndex != -1) {
                    baseUri = originalBaseUri.substring(0, queryParamStartIndex - 1);
                    sseEndpoint = originalBaseUri.substring(queryParamStartIndex - 1);
                } else {
                    baseUri = originalBaseUri;
                    sseEndpoint = transportConfigSse.getSseEndpoint();
                }
                sseEndpoint = StringUtils.isBlank(sseEndpoint) ? "/sse" : sseEndpoint;

                HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                        .builder(baseUri) // 使用截取后的 baseUri
                        .sseEndpoint(sseEndpoint) // 使用截取或默认的 sseEndpoint
                        .build();

                McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(aiClientToolMcpVO.getRequestTimeout())).build();
                var init_sse = mcpSyncClient.initialize();

                log.info("Tool SSE MCP Initialized {}", init_sse);
                return mcpSyncClient;
            }
            case "stdio" ->{
                // 从配置中获取 stdio 命令
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio
                        = aiClientToolMcpVO.getTransportConfigStdio();
                Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdioMap
                        = transportConfigStdio.getStdio();
                AiClientToolMcpVO.TransportConfigStdio.Stdio stdio
                        = stdioMap.get(aiClientToolMcpVO.getMcpName());
                if (stdio == null) {
                    throw new IllegalArgumentException("stdio 配置缺少 MCP 名称: " + aiClientToolMcpVO.getMcpName());
                }
                // https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem
                var stdioParams = ServerParameters.builder(stdio.getCommand())
                        .args(resolveStdioArgs(stdio))
                        .env(stdio.getEnv())
                        .build();

                var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams))
                        .requestTimeout(Duration.ofSeconds(aiClientToolMcpVO.getRequestTimeout())).build();
                var init_stdio = mcpClient.initialize();

                log.info("Tool Stdio MCP Initialized {}", init_stdio);
                return mcpClient;
            }
            case "streamable-http" -> {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode config = mapper.readTree(aiClientToolMcpVO.getTransportConfig());
                    String endpoint = firstNonBlank(
                            config.path("baseUri").asText(null),
                            config.path("url").asText(null),
                            config.path("endpoint").asText(null));
                    if (StringUtils.isBlank(endpoint)) {
                        throw new IllegalArgumentException("streamable-http requires baseUri, url, or endpoint");
                    }
                    Map<String, String> headers = config.has("headers")
                            ? mapper.convertValue(config.get("headers"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {})
                            : Map.of();
                    int timeout = aiClientToolMcpVO.getRequestTimeout() == null
                            ? 60 : aiClientToolMcpVO.getRequestTimeout();
                    HttpClient httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(Math.max(1, timeout)))
                            .build();
                    McpSyncClient mcpClient = McpClient.sync(new StreamableHttpClientTransport(
                                    endpoint, mapper, httpClient, headers))
                            .requestTimeout(Duration.ofSeconds(Math.max(1, timeout)))
                            .build();
                    var initStreamable = mcpClient.initialize();
                    log.info("Tool Streamable HTTP MCP Initialized {}", initStreamable);
                    return mcpClient;
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid streamable-http MCP configuration", e);
                }
            }
        }
        throw new RuntimeException("err! transportType " + transportType + " not exist!");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) return value;
        }
        return null;
    }

    /**
     * Spring AI's MCP stdio transport does not expose a working-directory setter.
     * Resolve relative script arguments before creating the child process so an
     * IDEA run configuration can safely use a project-relative path.
     */
    public static List<String> resolveStdioArgs(AiClientToolMcpVO.TransportConfigStdio.Stdio stdio) {
        List<String> args = stdio.getArgs() == null ? List.of() : stdio.getArgs();
        String configuredDirectory = stdio.getWorkingDirectory();
        if (configuredDirectory == null || configuredDirectory.isBlank()) return args;
        Path workingDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
        return args.stream().map(argument -> {
            if (argument == null || argument.isBlank()) return argument;
            Path candidate = Path.of(argument);
            if (candidate.isAbsolute()) return argument;
            Path resolved = workingDirectory.resolve(candidate).normalize();
            return Files.exists(resolved) ? resolved.toString() : argument;
        }).toList();
    }

    /** Returns the server-advertised MCP tools for progressive disclosure. */
    public List<McpSchema.Tool> listTools(String mcpId) {
        try {
            McpSyncClient client = getBean(beanName(mcpId));
            return client.listTools().tools();
        } catch (Exception e) {
            log.warn("读取 MCP 工具列表失败: mcpId={}, message={}", mcpId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 动态注册 MCP 同步客户端。
     * 供 AgentController 在保存 MCP 配置后直接调用，无需重启。
     */
    public void registerMcpSyncClient(String mcpId, String mcpName, String transportType, String transportConfig, int requestTimeout) {
        try {
            AiClientToolMcpVO vo = new AiClientToolMcpVO();
            vo.setMcpId(mcpId);
            vo.setMcpName(mcpName);
            vo.setTransportType(transportType);
            vo.setTransportConfig(transportConfig);
            vo.setRequestTimeout(requestTimeout);

            if ("sse".equals(transportType) && transportConfig != null) {
                ObjectMapper mapper = new ObjectMapper();
                AiClientToolMcpVO.TransportConfigSse cfg = mapper.readValue(transportConfig, AiClientToolMcpVO.TransportConfigSse.class);
                vo.setTransportConfigSse(cfg);
            } else if ("stdio".equals(transportType) && transportConfig != null) {
                String stdioJson = transportConfig.trim().startsWith("{" + "\"" + mcpName + "\"")
                        ? transportConfig
                        : "{\"" + mcpName + "\":" + transportConfig + "}";
                Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdioMap = com.alibaba.fastjson.JSON.parseObject(
                        stdioJson, new com.alibaba.fastjson.TypeReference<Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio>>() {});
                AiClientToolMcpVO.TransportConfigStdio stdioCfg = new AiClientToolMcpVO.TransportConfigStdio();
                stdioCfg.setStdio(stdioMap);
                vo.setTransportConfigStdio(stdioCfg);
            }

            McpSyncClient client = createMcpSyncClient(vo);
            registerBean(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId), McpSyncClient.class, client);
            log.info("动态注册 MCP 客户端成功: {}", mcpId);
        } catch (Exception e) {
            log.error("动态注册 MCP 客户端失败: {}", e.getMessage(), e);
        }
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName();
    }
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientModelNode;
    }

}
