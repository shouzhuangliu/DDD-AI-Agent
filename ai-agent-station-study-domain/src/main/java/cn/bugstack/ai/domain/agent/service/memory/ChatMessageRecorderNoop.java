package cn.bugstack.ai.domain.agent.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
public class ChatMessageRecorderNoop implements ChatMessageRecorder {

    @Override public void recordUser(String s, String a, int t, String c) { log.trace("NOOP user: {}", s); }
    @Override public void recordAssistant(String s, String a, int t, int st, String c, String tc) { log.trace("NOOP asst: {}", s); }
    @Override public void recordTool(String s, String a, int t, int st, String id, String n, String args, String c) { log.trace("NOOP tool: {}", s); }
    @Override public List<HistoryMessage> getHistory(String s) { return List.of(); }
    @Override public String findByToolCallId(String id) { return null; }
    @Override public void markCompressed(long id) {}
    @Override public void recordLlmLog(LlmLogEntry e) { log.trace("NOOP llm: {}", e.getSessionId()); }
    @Override public List<?> queryLlmLogs(int limit) { return List.of(); }
    @Override public Object queryLlmLogById(Long id) { return null; }
    @Override public Object queryCases(String keyword, int limit) { return List.of(); }
    @Override public Object queryFeedback(int limit, String agentId) { return List.of(); }
}