package org.example.raglearn.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * 用轻量 LLM 调用做意图分类。
     * 延迟约 200-500ms，比关键词匹配准确率高得多。
     * 失败时回退到空字符串（none，不注入规则层）。
     */
    private String classifyIntent(String question) {
        String classifyPrompt = """
                将以下用户问题分类为 competition / paper / general / none，仅回复一个单词。

                competition：涉及竞赛、比赛、奖项、排名、名次、团队项目等
                paper：涉及论文、期刊、发表、作者署名等
                general：涉及CSP认证、外语等级、荣誉称号、学生任职、参军实习、创新项目、操行评等、活动表彰等
                none：与以上均不相关

                问题：%s
                分类：""".formatted(question);

        try {
            String result = chatClient.prompt()
                    .user(classifyPrompt)
                    .call()
                    .content();
            if (result != null) {
                String cleaned = result.strip().toLowerCase();
                if (cleaned.contains("competition")) return "competition";
                if (cleaned.contains("paper")) return "paper";
                if (cleaned.contains("general")) return "general";
                if (cleaned.contains("none")) return "";
            }
        } catch (Exception e) {
            log.warn("意图分类 LLM 调用失败，回退为 none", e);
        }
        return ""; // 兜底：不注入规则层
    }

    // ────────── 核心问答 ──────────

    public String getAnswer(String question) {

        // Step 1: AI 意图分类，选择规则层
        String intent = classifyIntent(question);
        String categoryRules = switch (intent) {
            case "competition" -> promptConfig.getCompetition();
            case "paper" -> promptConfig.getPaper();
            case "general" -> promptConfig.getGeneral();
            default -> ""; // none — 不注入规则
        };
        log.debug("意图: {} | 规则层: {}", intent.isEmpty() ? "none" : intent,
                intent.isEmpty() ? "无" : "已注入");

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
