package org.example.raglearn;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
@Slf4j
public class VectorTest {
    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void test() {
        var text = "你好，世界";
        var resp = embeddingModel.call(new EmbeddingRequest(List.of(text), null));
        String model = resp.getMetadata().getModel();
        log.info("模型: {}", model);
        float[] output = resp.getResult().getOutput();
        log.info("向量维度: {}", output.length);
    }

    @Test
    void test2() {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(List.of(new Document("你好，世界")));
        store.add(List.of(new Document("管理员可以通过该模块添加新用户")));
        store.add(List.of(new Document("管理员功能模块，添加用户功能")));
        store.add(List.of(new Document("管理员功能模块，更改用户权限")));

        SearchRequest req = SearchRequest.builder()
                .query("如何添加用户")
                .topK(5)
                .similarityThreshold(0.1)
                .build();

        store.similaritySearch(req)
                .forEach(doc -> {
                    log.info("{}, score: {}", doc.getText(), doc.getScore());
                });
    }
}
