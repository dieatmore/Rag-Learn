package org.example.raglearn.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
public class RagEvaluationTest {

    @Autowired
    private DeepEvalEvaluationClient deepEvalClient;

    @Autowired
    private HandbookService handbookService;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 结构化测试用例。
     */
    record TestCase(String question, Set<String> expectedIntents, List<String> keyFacts) {
        TestCase(String question, Set<String> expectedIntents, String... keyFacts) {
            this(question, expectedIntents, Arrays.asList(keyFacts));
        }
    }

    /**
     * 单条评估数据的载体，直接对应 DeepEval API 的 test_item 字段。
     */
    record EvalItem(String question, Set<String> expectedIntents, Set<String> actualIntents,
                    String answer, List<String> contexts, String groundTruth) {}

    // ── 检索 ──────────────────────────────────────────────────

    private List<String> retrieveContexts(String question, int topK) {
        return vectorStore.similaritySearch(
                        SearchRequest.builder().query(question).topK(topK).build())
                .stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }

    // ── 唯一入口：采集意图 + 答案 + 检索 → DeepEval 评估 ─────

    @Test
    void evaluate() {
        List<TestCase> cases = buildTestCases();
        System.out.println("===== DeepEval 数据采集，共 " + cases.size() + " 条用例（并发 3）=====");

        // 并发采集，限制并发避免 DashScope 限流
        List<EvalItem> items;
        try (var executor = Executors.newFixedThreadPool(3)) {
            items = cases.stream()
                    .map(tc -> CompletableFuture.supplyAsync(() -> {
                        Set<String> actualIntents = handbookService.classifyIntent(tc.question);
                        String answer = handbookService.getAnswer(tc.question);
                        List<String> contexts = retrieveContexts(tc.question, 5);
                        String groundTruth = String.join("；", tc.keyFacts);
                        return new EvalItem(tc.question, tc.expectedIntents, actualIntents,
                                answer, contexts, groundTruth);
                    }, executor))
                    .toList()
                    .stream()
                    .map(CompletableFuture::join)
                    .toList();
        }

        // ── 意图分类统计 ──
        System.out.println("\n===== 意图分类统计 =====");
        long intentHits = items.stream().filter(it -> it.expectedIntents.equals(it.actualIntents)).count();
        long multiTotal = items.stream().filter(it -> it.expectedIntents.size() > 1).count();
        long multiHits = items.stream()
                .filter(it -> it.expectedIntents.size() > 1 && it.expectedIntents.equals(it.actualIntents))
                .count();
        System.out.printf("意图准确率: %d/%d (%.1f%%)%n",
                intentHits, items.size(), 100.0 * intentHits / items.size());
        System.out.printf("多意图命中: %d/%d%n", multiHits, multiTotal);

        for (int i = 0; i < items.size(); i++) {
            EvalItem it = items.get(i);
            String match = it.expectedIntents.equals(it.actualIntents) ? "✓" :
                    "✗ 预期:" + it.expectedIntents + " 实际:" + it.actualIntents;
            System.out.printf("[%d/%d] %s | Q: %s%n", i + 1, items.size(), match,
                    it.question.substring(0, Math.min(40, it.question.length())));
        }

        // ── 组装 DeepEval 数据 ──
        List<Map<String, Object>> evalPayload = items.stream().map(it -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", it.question);
            m.put("answer", it.answer);
            m.put("contexts", it.contexts);
            m.put("ground_truth", it.groundTruth);
            return m;
        }).collect(Collectors.toList());

        System.out.println("\n===== 开始 DeepEval 评估（通义千问 Judge）=====");

        Map<String, Object> result = deepEvalClient.evaluateRAG(evalPayload);

        // ── 评估报告 ──
        System.out.println("\n===== DeepEval 测评报告 =====");
        System.out.println("Judge 模型: " + result.get("model"));
        System.out.printf("忠实度        (faithfulness)      : %.4f%n", result.get("average_faithfulness"));
        System.out.printf("回答相关性    (answer_relevancy)   : %.4f%n", result.get("average_answer_relevancy"));
        System.out.printf("上下文召回率  (context_recall)      : %.4f%n", result.get("average_context_recall"));
        System.out.printf("上下文精准率  (context_precision)   : %.4f%n", result.get("average_context_precision"));
        System.out.printf("幻觉检测      (hallucination)       : %.4f%n", result.get("average_hallucination"));
        System.out.println("用例数: " + result.get("test_count"));

        // 逐条详情
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("details");
        if (details != null) {
            System.out.println("\n── 逐条详情 ──");
            for (int i = 0; i < details.size(); i++) {
                Map<String, Object> d = details.get(i);
                System.out.printf("[%d] %s%n", i + 1, d.get("question"));
                System.out.printf("    faithfulness=%.4f  answer_relevancy=%.4f  context_recall=%.4f  context_precision=%.4f  hallucination=%.4f%n",
                        d.get("faithfulness"), d.get("answer_relevancy"),
                        d.get("context_recall"), d.get("context_precision"),
                        d.get("hallucination"));
            }
        }
    }

    // ── 测试用例 10 条 ──────────────────────────────────────────────

    private List<TestCase> buildTestCases() {
        List<TestCase> cases = new ArrayList<>();

        // ① 单意图：competition
        cases.add(new TestCase(
                "美国大学生数学建模竞赛拿了M奖，对应什么等级？能加多少分？",
                Set.of("competition"),
                "美赛M奖→省级一等奖", "科技竞赛II", "0.3分"
        ));

        // ② 单意图：paper
        cases.add(new TestCase(
                "以东北林业大学为第一署名单位，独立作者发表的中科院2区期刊论文能加多少分？",
                Set.of("paper"),
                "顶级期刊", "30分", "独立作者/第一作者"
        ));

        // ③ 单意图：general（CSP）
        cases.add(new TestCase(
                "CSP认证考了280分能加多少分？",
                Set.of("general"),
                "260-299分档", "2分", "限最高一次成绩"
        ));

        // ④ 单意图：general（外语+任职）
        cases.add(new TestCase(
                "英语六级450分能加多少分？",
                Set.of("general"),
                "国家六级合格标准", "10分"
        ));

        // ⑤ 多意图：competition + paper
        cases.add(new TestCase(
                "我发表了一篇中科院2区论文，同时在美赛拿了M奖，分别能加多少分？能同时加分吗？",
                Set.of("competition", "paper"),
                "中科院2区=顶级期刊30分", "美赛M奖→省级一等奖0.3分(科技竞赛II)",
                "论文和竞赛属于不同子类可同时加分"
        ));

        // ⑥ 多意图：competition + general
        cases.add(new TestCase(
                "我既是国家级大创项目负责人，又拿了科技竞赛I国家级一等奖，这两个能累计吗？",
                Set.of("competition", "general"),
                "大创属于其他方面2分", "科技竞赛I国家级一等奖3分",
                "不同一级指标可累计", "合计5分"
        ));

        // ⑦ 多意图：paper + general
        cases.add(new TestCase(
                "英语六级过了，又发了北大核心期刊论文，外语能力和学术论文能同时加分吗？",
                Set.of("paper", "general"),
                "外语能力10分", "北大核心期刊2分",
                "不同大类可累计", "合计12分"
        ));

        // ⑧ 多意图：三类混合
        cases.add(new TestCase(
                "我参加了美赛拿了M奖、发了一篇核心期刊、CSP考了280分、还是班长，帮我算算一共能加多少？",
                Set.of("competition", "paper", "general"),
                "美赛M奖0.3分", "核心期刊2分", "CSP 2分", "班长0.5分", "合计4.8分"
        ));

        // ⑨ 多意图：三类混合 + 上限
        cases.add(new TestCase(
                "国家级竞赛一等奖、中科院2区论文第一作者、省级三好学生，这三个方面的加分能全部累计吗？有没有总分上限？",
                Set.of("competition", "paper", "general"),
                "竞赛一等奖50分(学术专长)", "论文30分(学术专长)", "三好学生1分(其他方面)",
                "学术专长上限50分所以竞赛+论文取最高", "三好学生可另加"
        ));

        // ⑩ 边界：截止日期
        cases.add(new TestCase(
                "竞赛获奖日期是推免当年9月1日，还能算吗？",
                Set.of("competition"),
                "不能", "截止日期为推免当年8月31日"
        ));

        return cases;
    }
}
