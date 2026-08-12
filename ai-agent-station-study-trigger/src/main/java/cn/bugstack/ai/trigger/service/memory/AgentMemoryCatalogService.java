package cn.bugstack.ai.trigger.service.memory;

import cn.bugstack.ai.domain.agent.service.memory.AgentMemoryCatalogPort;
import cn.bugstack.ai.domain.agent.service.memory.LongTermMemoryPort;
import cn.bugstack.ai.infrastructure.dao.IAgentMemoryCardDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentMemoryCard;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentMemoryCatalogService implements AgentMemoryCatalogPort {

    private static final int MAX_SEARCH_RESULTS = 5;
    private static final int MAX_CONTENT_RESULTS = 3;
    private final IAgentMemoryCardDao cardDao;
    private final LongTermMemoryPort indexPort;

    public AgentMemoryCatalogService(IAgentMemoryCardDao cardDao, LongTermMemoryPort indexPort) {
        this.cardDao = cardDao;
        this.indexPort = indexPort;
    }

    @Override
    public List<MemoryIndexItem> search(String agentId, String query, int limit) {
        if (blank(agentId) || blank(query)) return List.of();
        int bounded = Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS);
        List<LongTermMemoryPort.MemoryIndexReference> semantic;
        try { semantic = indexPort.searchIndex(agentId, query, bounded); }
        catch (Exception ignored) { semantic = List.of(); }

        Map<String, Double> semanticScores = new LinkedHashMap<>();
        if (semantic != null) semantic.forEach(item -> {
            if (agentId.equals(item.agentId()) && !blank(item.memoryId())) semanticScores.putIfAbsent(item.memoryId(), item.score());
        });
        List<AgentMemoryCard> semanticCards = semanticScores.isEmpty() ? List.of()
                : safe(cardDao.queryPublishedByMemoryIds(agentId, new ArrayList<>(semanticScores.keySet())));
        List<AgentMemoryCard> lexicalCards = safe(cardDao.searchPublishedIndex(agentId, query, bounded));

        LinkedHashMap<String, MemoryIndexItem> merged = new LinkedHashMap<>();
        for (AgentMemoryCard card : semanticCards) addIfPublished(merged, agentId, card, semanticScores.getOrDefault(card.getMemoryId(), 0D));
        for (AgentMemoryCard card : lexicalCards) addIfPublished(merged, agentId, card, keywordScore(card, query));
        return merged.values().stream().sorted(Comparator.comparingDouble(MemoryIndexItem::score).reversed())
                .limit(bounded).toList();
    }

    @Override
    public List<MemoryContent> getPublished(String agentId, List<String> memoryIds) {
        if (blank(agentId) || memoryIds == null) return List.of();
        List<String> boundedIds = memoryIds.stream().filter(id -> !blank(id)).distinct().limit(MAX_CONTENT_RESULTS).toList();
        if (boundedIds.isEmpty()) return List.of();
        Map<String, AgentMemoryCard> cards = new HashMap<>();
        for (AgentMemoryCard card : safe(cardDao.queryPublishedByMemoryIds(agentId, boundedIds))) {
            if (isPublishedFor(agentId, card)) cards.put(card.getMemoryId(), card);
        }
        return boundedIds.stream().map(cards::get).filter(Objects::nonNull).map(this::toContent).toList();
    }

    private void addIfPublished(Map<String, MemoryIndexItem> result, String agentId, AgentMemoryCard card, double score) {
        if (!isPublishedFor(agentId, card)) return;
        result.putIfAbsent(card.getMemoryId(), new MemoryIndexItem(agentId, card.getMemoryId(), version(card),
                safe(card.getMemoryType()), safe(card.getTitle()), safe(card.getDescription()),
                safe(card.getSourceCaseId()), score));
    }

    private boolean isPublishedFor(String agentId, AgentMemoryCard card) {
        return card != null && agentId.equals(card.getAgentId()) && "PUBLISHED".equalsIgnoreCase(card.getStatus())
                && !Integer.valueOf(1).equals(card.getIsDeleted());
    }

    private MemoryContent toContent(AgentMemoryCard card) {
        return new MemoryContent(card.getAgentId(), card.getMemoryId(), version(card), safe(card.getMemoryType()),
                safe(card.getTitle()), safe(card.getDescription()), safe(card.getContentJson()), safe(card.getSourceCaseId()));
    }

    private double keywordScore(AgentMemoryCard card, String query) {
        String text = (safe(card.getTitle()) + " " + safe(card.getDescription()) + " " + safe(card.getMemoryKey())).toLowerCase();
        return text.contains(query.trim().toLowerCase()) ? 0.75D : 0.25D;
    }

    private static int version(AgentMemoryCard card) { return card.getVersion() == null ? 1 : card.getVersion(); }
    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) { return value == null ? "" : value; }
}
