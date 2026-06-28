package org.example.raglearn.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
public class RagEvaluationTest {
    @Autowired
    private RagasEvaluationClient ragasClient;

    @Autowired
    private HandbookService handbookService;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 核心工具方法：获取真实召回的上下文（原始文本，去掉过滤，只保留topK控制）
     * @param question 测试问题
     * @param topK 召回数量（建议论文场景设为2，竞赛场景设为3，避免过多无关内容）
     * @return 真实召回的上下文列表（原始文本）
     */
    private List<String> getRealContexts(String question, int topK) {
        // 构建检索请求
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .build();

        // 执行真实检索，获取原始Document
        List<Document> retrievedDocs = vectorStore.similaritySearch(searchRequest);

        // 提取原始文本（保留所有格式，无人工改写）
        return retrievedDocs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }

    @Test
    void testRAGEvaluation() {
        List<Map<String, Object>> testCases = new ArrayList<>();

        // 测试用例1：全国大学生机械创新设计大赛二等奖能加多少分？
        String q1 = "全国大学生机械创新设计大赛二等奖能加多少分？";
        Map<String, Object> testCase1 = new HashMap<>();
        testCase1.put("question", q1);
        testCase1.put("ground_truth", "全国大学生机械创新设计大赛属于第二等级学科竞赛，其二等奖可加30分；集体项目仅认定前两名，第三名无额外加分，且竞赛获奖实行代表作制，多项获奖不累计计分。");
        testCase1.put("contexts", getRealContexts(q1, 5));
        testCase1.put("answer", handbookService.getAnswer(q1));
        testCases.add(testCase1);

       // 测试用例2：中国国际互联网+大学生创新创业大赛一等奖属于第几等级竞赛？能加多少分？
        String q2 = "中国国际互联网+大学生创新创业大赛一等奖属于第几等级竞赛？能加多少分？";
        Map<String, Object> testCase2 = new HashMap<>();
        testCase2.put("question", q2);
        testCase2.put("ground_truth", "中国国际“互联网+”大学生创新创业大赛属于第一等级学科竞赛，其一等奖可加50分，且该竞赛获奖实行代表作制，同一项竞赛获不同等级奖项取最高等级计分。");
        testCase2.put("contexts", getRealContexts(q2, 5));
        testCase2.put("answer", handbookService.getAnswer(q2));
        testCases.add(testCase2);

       // 测试用例3：蓝桥杯全国软件和信息技术专业人才大赛二等奖能加多少分？
        String q3 = "蓝桥杯全国软件和信息技术专业人才大赛二等奖能加多少分？";
        Map<String, Object> testCase3 = new HashMap<>();
        testCase3.put("question", q3);
        testCase3.put("ground_truth", "蓝桥杯全国软件和信息技术专业人才大赛属于第三等级学科竞赛，其二等奖可加20分，竞赛获奖日期需截至推免当年8月31日才有效。");
        testCase3.put("contexts", getRealContexts(q3, 5));
        testCase3.put("answer", handbookService.getAnswer(q3));
        testCases.add(testCase3);

        // 测试用例4：以东北林业大学为第一署名单位，独立作者发表的中科院2区期刊论文能加多少分？
        String q4 = "以东北林业大学为第一署名单位，独立作者发表的中科院2区期刊论文能加多少分？";
        Map<String, Object> testCase4 = new HashMap<>();
        testCase4.put("question", q4);
        testCase4.put("ground_truth", "以东北林业大学为第一署名单位，独立作者发表的中科院2区期刊论文属于顶级期刊范畴，可加30分；论文实行代表作制，发表多篇不累计计分，且发表日期需截至推免当年8月31日。");
        testCase4.put("contexts", getRealContexts(q4, 5));
        testCase4.put("answer", handbookService.getAnswer(q4));
        testCases.add(testCase4);

        // 测试用例5：同时获得全国大学生数学竞赛一等奖和EI期刊论文（Ja检索），总计能加多少分？
        String q5 = "同时获得全国大学生数学竞赛一等奖和EI期刊论文（Ja检索），总计能加多少分？";
        Map<String, Object> testCase5 = new HashMap<>();
        testCase5.put("question", q5);
        testCase5.put("ground_truth", "全国大学生数学竞赛属于第二等级学科竞赛，一等奖可加40分；EI期刊论文（Ja检索）属于各专业领域高水平期刊，可加10分；学术专长总分50，其中竞赛总分50、论文总分30，但竞赛和论文分属不同类目，可累计计分，总计50分（竞赛40分+论文10分）。");
        testCase5.put("contexts", getRealContexts(q5, 5));
        testCase5.put("answer", handbookService.getAnswer(q5));
        testCases.add(testCase5);

        // 调用 RAGAS 服务
        Map<String, Object> result = ragasClient.evaluateRAG(testCases);

        // 输出报告
        System.out.println("===== RAGAS 测评报告 =====");
        System.out.println("忠实度: " + result.get("average_faithfulness"));
        System.out.println("回答相关性: " + result.get("average_answer_relevancy"));
        System.out.println("召回率: " + result.get("average_context_recall"));
        System.out.println("精准率: " + result.get("average_context_precision"));

        // 平均准确性: null
        // 平均相关性: 0.6430540286346331
        // 平均召回率: 0.9333333333333333
        // 平均精准率: 0.79999999992
    }
}
