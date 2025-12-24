package org.example.raglearn.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
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

        // 构建自定义Prompt模板
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

        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .promptTemplate(customPromptTemplate)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.4)
                        .topK(3)
                        .build())
                .build();

        return chatClient.prompt()
                .advisors(qaAdvisor)
                .user(question)
                .call()
                .content();
    }
}
