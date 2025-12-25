package org.example.raglearn.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;
@Service
@Slf4j
@RequiredArgsConstructor
public class HandbookService {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public String getAnswer(String question) {

        // 自定义Prompt模板1：无上下文时只允许说我不知道
        PromptTemplate customPromptTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .template("""
                    <query>

                    Context information is below.

                    ---------------------
                    <question_answer_context>
                    ---------------------

                    Given the context information and no prior knowledge, answer the query.

                    Follow these rules:

                    1. If the answer is not in the context, just say that you don't know.
                    2. Avoid statements like "Based on the context..." or "The provided information...".
                    """)
                .build();

        // 自定义Prompt模板2：无上下文时用自身知识回答
        // todo: 改为判断是否存在上下文，现在不存在时ai回答还是会参照上下文
        PromptTemplate withoutContextTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .template("""
                    <query>

                    Context information is below.

                    ---------------------
                    <context>
                    ---------------------

                    Given the context information and no prior knowledge, answer the query.

                    Follow these rules:

                    1. If the answer is not in the context, Answer the query with your own knowledge, be concise and accurate (in Chinese).
                    2. Avoid statements like "Based on the context..." or "The provided information...".
                    """)
                .build();

        // 无上下文自己回答
        var qaAdvisorWithout = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.4)
                        .topK(3)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .promptTemplate(withoutContextTemplate)
                        .build())
                .build();

        // 无上下文不知道
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .promptTemplate(customPromptTemplate)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.4)
                        .topK(5)
                        .build())
                .build();

        return chatClient.prompt()
                .advisors(qaAdvisor)
                .user(question)
                .call()
                .content();
    }
}
