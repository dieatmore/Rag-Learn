package org.example.raglearn.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepEval 评估客户端。
 * 调用 Python 端 FastAPI 服务（eval/run_deepeval.py），
 * 使用通义千问作为 DeepEval 的 Judge 模型。
 */
@Component
public class DeepEvalEvaluationClient {

    @Autowired
    private RestTemplate restTemplate;

    private static final String EVAL_API_URL = "http://localhost:8000/evaluate-rag";

    /**
     * 执行 RAG 评估。
     *
     * @param testCases 每条包含 question / answer / contexts / ground_truth
     * @return 评估报告，包含 average_faithfulness、average_answer_relevancy、
     *         average_context_recall、average_context_precision、
     *         average_hallucination 及逐条 details
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluateRAG(List<Map<String, Object>> testCases) {
        Map<String, Object> request = new HashMap<>();
        request.put("test_items", testCases);
        request.put("version", resolveVersion());
        return restTemplate.postForObject(EVAL_API_URL, request, Map.class);
    }

    /**
     * 版本标签策略：
     * 1. 环境变量 EVAL_VERSION 手动指定（如 "v2.0-release"）
     * 2. 否则自动取 git commit 短 hash（如 "a3f2c1b"）
     * 3. 取不到则回退到当天日期
     */
    private String resolveVersion() {
        String manual = System.getenv("EVAL_VERSION");
        if (manual != null && !manual.isBlank()) return manual;

        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(new java.io.File("."))
                    .redirectErrorStream(true)
                    .start();
            String hash = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !hash.isEmpty()) return hash;
        } catch (Exception ignored) {}

        return java.time.LocalDate.now().toString();
    }
}
