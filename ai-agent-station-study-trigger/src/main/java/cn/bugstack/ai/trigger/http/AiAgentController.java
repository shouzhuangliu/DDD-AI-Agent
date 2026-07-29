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
import cn.bugstack.ai.trigger.service.feedback.FeedbackAutoCaptureService;
import com.alibaba.fastjson.JSON;
import cn.bugstack.ai.domain.agent.service.execute.react.ReActExecuteResultEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
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

    private static final int PLAN_MAX_STEPS = 5;
    private static final int REACT_MAX_STEPS = 30;

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

    @Resource
    private FeedbackAutoCaptureService feedbackAutoCaptureService;

    @Resource
    private cn.bugstack.ai.domain.agent.service.tools.subagent.SubagentExecutionService subagentExecutionService;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.react.AgentExecutionCancellationRegistry executionCancellationRegistry;

    @Value("${spring.ai.agent.auto-config.enabled:false}")
    private boolean legacyAutoConfigEnabled;

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
            boolean explicitReact = "react".equalsIgnoreCase(reqMode);
            ChatAgentRoutePolicy.RouteDecision routeDecision = explicitReact
                    ? new ChatAgentRoutePolicy.RouteDecision("react", "显式选择 ReAct 模式")
                    : chatAgentRoutePolicy.route(request.getMessage(), reqMode);
            if ("feedback".equals(routeDecision.route())) {
                Long feedbackId = feedbackAutoCaptureService.captureUserIssue(request.getAiAgentId(), request.getSessionId(), request.getMessage());
                log.info("业务反馈已自动记录: agentId={}, sessionId={}, feedbackId={}", request.getAiAgentId(), request.getSessionId(), feedbackId);
            }
            String routedMode = ("plan".equals(routeDecision.route()) || "feedback".equals(routeDecision.route()))
                    ? ("feedback".equals(routeDecision.route()) ? ChatExecuteStrategy.TYPE : AiAgentModeEnum.AUTO.getCode())
                    : routeDecision.route();
            if (AiAgentModeEnum.AUTO.getCode().equals(routedMode)
                    && (!legacyAutoConfigEnabled || !hasCompleteAutoFlow(request.getAiAgentId()))) {
                log.warn("Agent {} 的旧 Auto Client 未完整启用，自动降级为 ReAct", request.getAiAgentId());
                routedMode = AiAgentModeEnum.REACT.getCode();
            }
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
            int maxStep = "react".equals(routedMode) ? REACT_MAX_STEPS : PLAN_MAX_STEPS;

            // 3. 构建执行命令实体
            ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
                    .aiAgentId(request.getAiAgentId())
                    .message(request.getMessage())
                    .sessionId(request.getSessionId())
                    .maxStep(maxStep)
                    .modelId(request.getModelId())
                    .routeType(routedMode)
                    .routeReason(routeDecision.reason())
                    .build();

            // 4. 异步执行
            threadPoolExecutor.execute(() -> {
                try {
                    finalStrategy.execute(executeCommandEntity, emitter);
                } catch (Exception e) {
                    log.error("执行异常[{}]：{}", finalStrategy.getType(), e.getMessage(), e);
                    try {
                        emitter.send("data: " + JSON.toJSONString(
                                ReActExecuteResultEntity.createError(e.getMessage(), request.getSessionId())) + "\n\n");
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
                errorEmitter.send("data: " + JSON.toJSONString(
                        ReActExecuteResultEntity.createError(e.getMessage(), request == null ? null : request.getSessionId())) + "\n\n");
                errorEmitter.complete();
            } catch (Exception ex) {
                log.error("发送错误信息失败：{}", ex.getMessage(), ex);
            }
            return errorEmitter;
        }
    }

    private boolean hasCompleteAutoFlow(String agentId) {
        var flow = agentRepository.queryAiAgentClientFlowConfig(agentId);
        if (flow == null || flow.isEmpty()) return false;
        return flow.containsKey("TASK_ANALYZER_CLIENT")
                && flow.containsKey("PRECISION_EXECUTOR_CLIENT")
                && flow.containsKey("QUALITY_SUPERVISOR_CLIENT")
                && flow.containsKey("RESPONSE_ASSISTANT");
    }

    /**
     * 请求取消主 Agent 执行。设置 executionId 的取消标志，主 ReAct 自持循环在下一次工具调用间隙
     * 或模型调用返回后检测并抛 CancellationException 退出。正在进行的单次 LLM HTTP 调用需等其返回。
     * 前端从 execution_started / state_updated SSE 事件拿到 executionId 后调用本端点。
     */
    @PostMapping("/executions/{executionId}/cancel")
    public Map<String, Object> cancelExecution(@PathVariable("executionId") String executionId) {
        boolean accepted = executionCancellationRegistry.cancel(executionId);
        return Map.of("success", accepted, "executionId", executionId,
                "status", accepted ? "CANCEL_REQUESTED" : "NOT_FOUND_OR_TERMINAL");
    }

    /**
     * 请求取消一个 Subagent 任务。设置 cancelRequested 标志：
     * 未启动则直接置 CANCELLED；运行中在工具调用间隙退出；已终态则忽略。
     * 前端从 subagent_started SSE 事件拿到 taskId 后调用本端点。
     */
    @PostMapping("/subagents/{taskId}/cancel")
    public Map<String, Object> cancelSubagent(@PathVariable("taskId") String taskId) {
        boolean accepted = subagentExecutionService.cancel(taskId);
        var state = subagentExecutionService.find(taskId);
        String status = state == null ? "NOT_FOUND" : state.getStatus();
        return Map.of("success", accepted, "taskId", taskId, "status", status);
    }

}
