package cn.bugstack.ai.domain.agent.service.armory.factory.element;

import com.alibaba.fastjson.JSON;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RagAnswerAdvisor implements BaseAdvisor {

    private final VectorStore vectorStore;
    private final SearchRequest searchRequest;
    private final String userTextAdvise;

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest) {
        this.vectorStore = vectorStore;
        this.searchRequest = searchRequest;
        this.userTextAdvise = "\nContext information is below, surrounded by ---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        HashMap<String, Object> context = new HashMap(chatClientRequest.context());
        // 1. 拿到老板的问题
        String userText = chatClientRequest.prompt().getUserMessage().getText();
        // 2. 把老板的问题和那张“便签纸模板”订书机订在一起
        String advisedUserText = userText + System.lineSeparator() + this.userTextAdvise;

        String query = (new PromptTemplate(userText)).render();

        SearchRequest searchRequestToUse = SearchRequest.from(this.searchRequest).query(query).filterExpression(this.doGetFilterExpression(context)).build();
        // 3. 秘书跑去档案室找资料了！
        // 拿着老板的问题 (query)，加上过滤条件，去档案室 (vectorStore) 搜索。
        List<Document> documents = this.vectorStore.similaritySearch(searchRequestToUse);
        context.put("qa_retrieved_documents", documents);
        // 4. 秘书把抱回来的书（多份文档），拼成一大段纯文本
        String documentContext = documents.stream().map(Document::getText).collect(Collectors.joining(System.lineSeparator()));
        // 5. 准备填空
        Map<String, Object> advisedUserParams = new HashMap(chatClientRequest.context());
        // 秘书把刚拼好的书本内容，命名为 "question_answer_context"
        advisedUserParams.put("question_answer_context", documentContext);

        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(new UserMessage(advisedUserText), new AssistantMessage(JSON.toJSONString(advisedUserParams))).build())
                .context(advisedUserParams)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        // 把这次查了哪些资料，记在小本本（metadata）上，方便查账。
        ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        chatResponseBuilder.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));
        ChatResponse chatResponse = chatResponseBuilder.build();

        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        // 秘书的完整一天：
        // 1. 先干 before (找资料拼便签)
        // 2. call (去问大模型)
        // 3. 返回后干 after (记账)
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(chatClientResponse, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    protected Filter.Expression doGetFilterExpression(Map<String, Object> context) {
        return context.containsKey("qa_filter_expression") && StringUtils.hasText(context.get("qa_filter_expression").toString()) ? (new FilterExpressionTextParser()).parse(context.get("qa_filter_expression").toString()) : this.searchRequest.getFilterExpression();
    }

}
