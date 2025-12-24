package org.example.raglearn.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;
@Service
@Slf4j
@RequiredArgsConstructor
public class HandbookService {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public String getAnswer(String question) {
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
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
