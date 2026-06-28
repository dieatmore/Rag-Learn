package org.example.raglearn.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RagasEvaluationClient {
    @Autowired
    private RestTemplate restTemplate;

    private static final String RAGAS_API_URL = "http://localhost:8000/evaluate-rag";

    public Map<String, Object> evaluateRAG(List<Map<String, Object>> testCases) {
        Map<String, Object> request = new HashMap<>();
        request.put("test_items", testCases);
        return restTemplate.postForObject(RAGAS_API_URL, request, Map.class);
    }
}
