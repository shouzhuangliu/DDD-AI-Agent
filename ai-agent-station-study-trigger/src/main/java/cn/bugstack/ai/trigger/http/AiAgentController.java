package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAiAgentService;
import cn.bugstack.ai.api.dto.AutoAgentRequestDTO;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentModeEnum;
import cn.bugstack.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.chat.ChatExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.route.ChatAgentRoutePolicy;
import cn.bugstack.ai.domain.agent.service.model.ModelSelectionService;
import cn.bugstack.ai.trigger.service.conversation.ConversationSessionService;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AutoAgent 自动智能对话体
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AiAgentController implements IAiAgentService {

    @Resource
    private List<IExecuteStrategy> executeStrategies;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private IAgentRepository agentRepository;

    @Resource
    private ModelSelectionService modelSelectionService;

    @Resource
    private ConversationSessionService conversationSessionService;

    @Resource
    private ChatAgentRoutePolicy chatAgentRoutePolicy;

    /** 模式 -> 策略，启动时按 getType() 建立 */
    private final Map<String, IExecuteStrategy> strategyMap = new HashMap<>();

    @PostConstruct
    public void initStrategyMap() {
        for (IExecuteStrategy strategy : executeStrategies) {
            strategyMap.put(strategy.getType(), strategy);
            log.info("注册执行策略: {} -> {}", strategy.getType(), strategy.getClass().getSimpleName());
        }
    }

    @RequestMapping(value = "auto_agent", method = RequestMethod.POST)
    public ResponseBodyEmitter autoAgent(@RequestBody AutoAgentRequestDTO request, HttpServletResponse response) {
        log.info("AutoAgent流式执行请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            // 设置SSE响应头
            conversationSessionService.requireOwned(request.getAiAgentId(), request.getSessionId());
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");

            // 1. 创建流式输出对象
            ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);

            // 模型必须在本次启动装配成功，避免把无效请求交给异步执行线程。
            modelSelectionService.requireAvailable(request.getModelId());

            // 2. 按请求 mode 选择策略（缺省则查 agent channel 自动选择）
            String reqMode = request.getMode();
            if (reqMode == null || reqMode.isBlank()) {
                var agent = agentRepository.queryAgentById(request.getAiAgentId());
                if (agent != null && "react".equals(agent.getChannel())) {
                    reqMode = "react";
                }
            }
            ChatAgentRoutePolicy.RouteDecision routeDecision = chatAgentRoutePolicy.route(request.getMessage(), reqMode);
            String routedMode = "plan".equals(routeDecision.route()) ? AiAgentModeEnum.AUTO.getCode() : routeDecision.route();
            IExecuteStrategy strategy = strategyMap.get(routedMode);
            if (strategy == null) {
                AiAgentModeEnum mode = AiAgentModeEnum.getByCode(routedMode);
                strategy = strategyMap.get(mode.getCode());
            }
            if (strategy == null) {
                strategy = strategyMap.get(ChatExecuteStrategy.TYPE);
            }
            if (strategy == null) {
                strategy = strategyMap.get(AiAgentModeEnum.AUTO.getCode());
            }
            log.info("Chat/Agent 协同路由: agentId={}, sessionId={}, preferredMode={}, route={}, strategy={}, reason={}",
                    request.getAiAgentId(), request.getSessionId(), reqMode, routeDecision.route(), strategy.getType(), routeDecision.reason());
            final IExecuteStrategy finalStrategy = strategy;

            // 3. 构建执行命令实体
            ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
                    .aiAgentId(request.getAiAgentId())
                    .message(request.getMessage())
                    .sessionId(request.getSessionId())
                    .maxStep(request.getMaxStep())
                    .modelId(request.getModelId())
                    .build();

            // 4. 异步执行
            threadPoolExecutor.execute(() -> {
                try {
                    finalStrategy.execute(executeCommandEntity, emitter);
                } catch (Exception e) {
                    log.error("执行异常[{}]：{}", finalStrategy.getType(), e.getMessage(), e);
                    try {
                        emitter.send("执行异常：" + e.getMessage());
                    } catch (Exception ex) {
                        log.error("发送异常信息失败：{}", ex.getMessage(), ex);
                    }
                } finally {
                    try {
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("完成流式输出失败：{}", e.getMessage(), e);
                    }
                }
            });

            return emitter;

        } catch (Exception e) {
            log.error("请求处理异常：{}", e.getMessage(), e);
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
            try {
                errorEmitter.send("请求处理异常：" + e.getMessage());
                errorEmitter.complete();
            } catch (Exception ex) {
                log.error("发送错误信息失败：{}", ex.getMessage(), ex);
            }
            return errorEmitter;
        }
    }

}
