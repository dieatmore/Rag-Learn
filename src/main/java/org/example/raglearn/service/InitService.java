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

    // DashScope嵌入模型的批量请求上限（固定为10）
    private static final int DASHSCOPE_BATCH_LIMIT = 10;
    private final VectorStore vectorStore;
    @Value("classpath:eventlist.txt")
    private Resource txtSource;

    // 启动时，将手册插入qdrant数据库
    @EventListener(ApplicationReadyEvent.class)
    public void initHandbook() throws Exception {
        String script = txtSource.getContentAsString(Charset.defaultCharset());
        String firstLine = script.split("\\R", 2)[0];
        // 取第一个模块名称，判断数据库是否已经存在
        SearchRequest request = SearchRequest.builder()
                .filterExpression(new FilterExpressionBuilder()
                        .eq("title", firstLine)
                        .build())
                .query("")
                .build();
        List<Document> documents = Optional.ofNullable(vectorStore.similaritySearch(request))
                .orElse(List.of());
        if (!documents.isEmpty()) {
            return;
        }
        Optional<QdrantClient> nativeClient = vectorStore.getNativeClient();
        if (nativeClient.isEmpty()) {
            return;
        }
        // 为`title`字段添加索引：Qdrant 的title是「Payload 字段」（非向量字段，用于过滤 / 检索）
        // 如果不创建索引，查询时会全量扫描所有数据，效率极低
        nativeClient.get()
                .createPayloadIndexAsync(
                        "my-vectors",
                        "title",
                        Collections.PayloadSchemaType.Keyword,
                        null,
                        true,
                        null,
                        null)
                .get();
        // 空行分割成多个独立段
        String[] split = script.split("\\r?\\n\\s*\\r?\\n");
        List<Document> docs = new ArrayList<>();
        // Map.of("title", title)）：元数据（Payload 字段），非向量数据，用于后续过滤 / 查询
        for (String info : split) {
            var title = info.split("\\R", 2)[0];
            var doc = new Document(info, Map.of("title", title));
            docs.add(doc);
        }
        List<List<Document>> batches = splitIntoBatches(docs, DASHSCOPE_BATCH_LIMIT);
        for (int i = 0; i < batches.size(); i++) {
            List<Document> batch = batches.get(i);
            log.info("提交第 {} 批Document，数量：{}", i + 1, batch.size());
            vectorStore.add(batch);
        }
    }

    private List<List<Document>> splitIntoBatches(List<Document> documents, int batchSize) {
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, documents.size());
            List<Document> batch = new ArrayList<>(documents.subList(i, endIndex));
            batches.add(batch);
        }
        return batches;
    }
}
