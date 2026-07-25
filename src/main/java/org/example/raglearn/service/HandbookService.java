package org.example.raglearn.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashSet;
import java.util.Set;
import org.example.raglearn.config.PromptConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandbookService {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final PromptConfig promptConfig;

    /**
     * 用轻量 LLM 调用做多意图分类（一个问题可能同时涉及多个类别）。
     * 延迟约 200-500ms，比关键词匹配准确率高得多。
     * 失败时回退到空集合（不注入规则层）。
     */
    Set<String> classifyIntent(String question) {
        String classifyPrompt = """
                将以下用户问题分类，可同时属于多个类别。仅回复逗号分隔的类别单词，如 "competition, paper"。

                competition：涉及竞赛、比赛、奖项、排名、名次、团队项目等
                paper：涉及论文、期刊、发表、作者署名等
                general：涉及CSP认证、外语等级、荣誉称号、学生任职、参军实习、创新项目、操行评等、活动表彰等
                若与以上均不相关，回复 none

                问题：%s
                分类：""".formatted(question);

        try {
            String result = chatClient.prompt()
                    .user(classifyPrompt)
                    .call()
                    .content();
            if (result != null) {
                Set<String> intents = new LinkedHashSet<>();
                String cleaned = result.strip().toLowerCase();
                if (cleaned.contains("competition")) intents.add("competition");
                if (cleaned.contains("paper")) intents.add("paper");
                if (cleaned.contains("general")) intents.add("general");
                if (cleaned.contains("none")) return Set.of();
                if (!intents.isEmpty()) return intents;
            }
        } catch (Exception e) {
            log.warn("意图分类 LLM 调用失败，回退为 none", e);
        }
        return Set.of(); // 兜底：不注入规则层
    }

    // ────────── 核心问答 ──────────

    public String getAnswer(String question) {

        // Step 1: AI 多意图分类，拼接多个规则层
        Set<String> intents = classifyIntent(question);
        StringBuilder rulesBuilder = new StringBuilder();
        for (String intent : intents) {
            String rule = switch (intent) {
                case "competition" -> promptConfig.getCompetition();
                case "paper" -> promptConfig.getPaper();
                case "general" -> promptConfig.getGeneral();
                default -> null;
            };
            if (rule != null && !rule.isEmpty()) {
                if (!rulesBuilder.isEmpty()) rulesBuilder.append("\n");
                rulesBuilder.append(rule);
            }
        }
        String categoryRules = rulesBuilder.toString();

        // Step 2: 三层拼装：base + 规则层 + output
        StringBuilder prompt = new StringBuilder(promptConfig.getBase());
        if (!categoryRules.isEmpty()) {
            prompt.append("\n").append(categoryRules);
        }
        prompt.append("\n").append(promptConfig.getOutput());

        // Step 3: 构建 PromptTemplate（沿用原有 StTemplateRenderer + QuestionAnswerAdvisor）
        String indented = "    " + prompt.toString().replace("\n", "\n    ");
        PromptTemplate customPromptTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder()
                        .startDelimiterToken('<')
                        .endDelimiterToken('>')
                        .build())
                .template("""
                        <query>

                           上下文信息如下。
                           ---------------------
                           <question_answer_context>
                           ---------------------
                        """ + indented + "\n")
                .build();

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
