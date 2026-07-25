package org.example.raglearn.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class InitService {

    private static final int DASHSCOPE_BATCH_LIMIT = 10;
    private final VectorStore vectorStore;

    @Value("classpath:eventlist.txt")
    private Resource txtSource;

    @Value("classpath:pdf_structured_manual.md")
    private Resource pdfSource;

    // ────────── eventlist 竞赛目录初始化 ──────────

    @EventListener(ApplicationReadyEvent.class)
    public void initHandbook() throws Exception {
        String script = txtSource.getContentAsString(Charset.defaultCharset());
        String firstLine = script.split("\\R", 2)[0];
        SearchRequest request = SearchRequest.builder()
                .filterExpression(new FilterExpressionBuilder()
                        .eq("title", firstLine)
                        .build())
                .query("")
                .build();
        List<Document> documents = Optional.ofNullable(vectorStore.similaritySearch(request))
                .orElse(List.of());
        if (!documents.isEmpty()) {
            log.info("eventlist 已存在，跳过初始化");
        } else {
            ensureTitleIndex();
            String[] split = script.split("\\r?\\n\\s*\\r?\\n");
            List<Document> docs = new ArrayList<>();
            for (String info : split) {
                var title = info.split("\\R", 2)[0];
                var doc = new Document(info, Map.of("title", title, "doc_source", "eventlist"));
                docs.add(doc);
            }
            batchInsert(docs, "eventlist");
        }

        // eventlist 完成后，继续初始化 PDF 结构化数据
        initPdfManual();
    }

    // ────────── PDF 结构化文档初始化 ──────────

    private void initPdfManual() throws Exception {
        String content = pdfSource.getContentAsString(Charset.defaultCharset());

        // 用 doc_source 字段判断是否已初始化
        SearchRequest request = SearchRequest.builder()
                .filterExpression(new FilterExpressionBuilder()
                        .eq("doc_source", "pdf_manual")
                        .build())
                .query("")
                .build();
        List<Document> existing = Optional.ofNullable(vectorStore.similaritySearch(request))
                .orElse(List.of());
        if (!existing.isEmpty()) {
            log.info("PDF 结构化文档已存在，跳过初始化");
            return;
        }

        List<Document> docs = parsePdfManual(content);
        batchInsert(docs, "PDF结构化文档");
    }

    /**
     * 按 ## 一级指标 → ### 二级指标 切分文档。
     * 每个二级指标为一个独立 chunk，携带元数据（l1, l2, category）。
     */
    private List<Document> parsePdfManual(String content) {
        List<Document> docs = new ArrayList<>();

        // 去掉文件头（# 标题 + 第一段说明）
        // 按 ## 分割出一级指标
        String[] l1Sections = content.split("\\n## ");
        // l1Sections[0] 是头部（标题+说明），从 [1] 开始是各一级指标
        // 还要处理 ## 通用备注

        for (int i = 1; i < l1Sections.length; i++) {
            String section = l1Sections[i];
            String l1Name = extractL1Name(section);

            // 跳过"通用备注"（非指标，不需要独立检索）
            if (l1Name.contains("通用备注")) {
                continue;
            }

            // 在一级指标内按 ### 分割出二级指标
            String[] l2Sections = section.split("\\n### ");
            // l2Sections[0] 是一级指标自身的描述行
            // 从 [1] 开始是各二级指标
            for (int j = 1; j < l2Sections.length; j++) {
                String l2Block = l2Sections[j];
                String l2Name = l2Block.split("\\R", 2)[0].strip();

                // 构造完整文本：一级标题 + 二级标题 + 内容
                String fullText = "## " + l1Name + "\n### " + l2Block;

                var doc = new Document(fullText, Map.of(
                        "title", l2Name,
                        "l1", l1Name,
                        "doc_source", "pdf_manual"
                ));
                docs.add(doc);
            }
        }

        log.info("PDF 结构化文档解析完成，共 {} 个 chunk", docs.size());
        return docs;
    }

    /** 从 "【学术专长】> 总分上限: 50分" 中提取一级指标名 */
    private String extractL1Name(String section) {
        String firstLine = section.split("\\R", 2)[0].strip();
        // 去掉 "> 总分上限: XX分" 后缀
        return firstLine.replaceAll(">.*", "").strip();
    }

    // ────────── 通用工具方法 ──────────

    private void ensureTitleIndex() throws Exception {
        Optional<QdrantClient> nativeClient = vectorStore.getNativeClient();
        if (nativeClient.isEmpty()) return;
        nativeClient.get()
                .createPayloadIndexAsync(
                        "my-vectors",
                        "title",
                        Collections.PayloadSchemaType.Keyword,
                        null, true, null, null)
                .get();
    }

    private void batchInsert(List<Document> docs, String label) {
        List<List<Document>> batches = splitIntoBatches(docs, DASHSCOPE_BATCH_LIMIT);
        for (int i = 0; i < batches.size(); i++) {
            List<Document> batch = batches.get(i);
            log.info("提交 [{}] 第 {} 批，数量：{}", label, i + 1, batch.size());
            vectorStore.add(batch);
        }
    }

    private List<List<Document>> splitIntoBatches(List<Document> documents, int batchSize) {
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, documents.size());
            batches.add(new ArrayList<>(documents.subList(i, endIndex)));
        }
        return batches;
    }
}
