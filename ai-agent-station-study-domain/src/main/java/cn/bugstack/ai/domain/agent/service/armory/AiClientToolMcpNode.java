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
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
                // http://127.0.0.1:9999/sse?apikey=REDACTED_MCP_API_KEY
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
                // https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem
                var stdioParams = ServerParameters.builder(stdio.getCommand())
                        .args(stdio.getArgs())
                        .env(stdio.getEnv())
                        .build();

                var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams))
                        .requestTimeout(Duration.ofSeconds(aiClientToolMcpVO.getRequestTimeout())).build();
                var init_stdio = mcpClient.initialize();

                log.info("Tool Stdio MCP Initialized {}", init_stdio);
                return mcpClient;
            }
        }
        throw new RuntimeException("err! transportType " + transportType + " not exist!");
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
