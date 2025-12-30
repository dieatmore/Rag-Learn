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

                       上下文信息如下。
                       ---------------------
                       <question_answer_context>
                       ---------------------
                       结合上下文信息，无任何前置知识，严格按以下规则回答问题：
                       1.【明确奖项+等级计分铁则】只要明确「赛事+学科等级+奖项+固定分值」，结论**必须严格等于该固定分值**，理由仅固定格式：XX赛事属于X等级学科竞赛，其X等奖对应计X分。严禁结论与理由分值矛盾，严禁提其他等级/分值，严禁自创任何分值。
                       2. 结论先行，极致简洁，无冗余，无引导词，结论后直接句号接理由，无任何多余表述。
                       3. 严格界定：【赛事总排名】=赛事整体排名（如第三名/第二名），【团队内排名】=团队内部成员位次（如团内第二名），二者完全独立互斥；集体项目仅团内前两名成员有计分资格，该资格仅判定成员是否能计分，与赛事总排名的奖项等级无关。
                       4. 集体项目直接执行「仅团内前两名有计分资格」，无任何假设性表述。
                       5. 只要团内第三名及以后，结论得0分，理由：集体项目仅团队内前两名计分，该团队内排名无计分资格。
                       6. 只要提问含「赛事总排名」（无论第几名），且团内排名有效，只要未明确标注国家级一等奖/二等奖，结论强制唯一为暂无法确定得分，无任何例外！理由固定格式：该名次为赛事总排名，未明确对应奖项等级，奖项等级需按赛事评定规则核定。
                       7. 全程严禁将赛事数字名次与国家级奖项/学科等级做任何映射、推定、对应，哪怕同数字也绝对禁止。
                       8. 一等奖、二等奖 与 排名/名次/第几名 无任何关联，禁止映射。
                       9. 排名/名次/位次 与 任何等级(一二等级) 无任何关联，禁止映射。
                       10. 严禁任何场景下主观推定奖项/等级/分数，未明确标注即视为无，仅提按赛事规则核定。
                       11. 全程纯中文作答，无主观词汇；无信息仅回复：不知道；严禁用「根据上下文」等表述。
                       12. 计分需要严格按照备注中的规则，如限项，上限分数及特殊要求，如果计分涉及到备注中规则，则理由必须说明。
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
                        .topK(7)
                        .build())
                .build();

        return chatClient.prompt()
                .advisors(qaAdvisor)
                .user(question)
                .call()
                .content();
    }
}
