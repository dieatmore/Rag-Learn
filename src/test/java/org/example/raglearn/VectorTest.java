package org.example.raglearn;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@SpringBootTest
@Slf4j
public class VectorTest {
    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private VectorStore vectorStore;

    @Test
    void test() {
        var text = "你好，世界";
        EmbeddingResponse resp = embeddingModel.call(new EmbeddingRequest(List.of(text), null));
        String model = resp.getMetadata().getModel();
        log.info("原始返回: {}", resp);
        log.info("模型: {}", model);
        float[] output = resp.getResult().getOutput();
        log.debug("{}", output.length);
        String vectors = Arrays.toString(output);
        log.debug("{}", vectors.length());
        log.debug("{}", vectors);
    }

    //
    @Test
    void test2() {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(List.of(new Document("你好，世界"))); // score: 0.2822206493883509
        store.add(List.of(new Document("管理员可以通过该模块添加新用户"))); // score: 0.7588845100346012
        store.add(List.of(new Document("管理员功能模块，添加用户功能"))); // score: 0.6741421791998747
        store.add(List.of(new Document("管理员功能模块，更改用户权限"))); // score: 0.5233625401966238

        SearchRequest req = SearchRequest.builder()
                .query("如何添加用户")
                .topK(5)
                .similarityThreshold(0.1)
                .build();

        // 相似度得分
        store.similaritySearch(req)
                .forEach(doc -> {
                    log.debug("{}, score: {}", doc.getText(), doc.getScore());
                });
    }


    // inset data
    @Test
    void addDocument() {
        vectorStore.add(List.of(new Document("你好，世界")));
        vectorStore.add(List.of(new Document("管理员可以通过该模块添加新用户")));
        vectorStore.add(List.of(new Document("管理员功能模块，添加用户功能")));
        vectorStore.add(List.of(new Document("管理员功能模块，更改用户权限")));
    }

    @Test
    void test4() {
        SearchRequest req = SearchRequest.builder()
                .query("如何添加用户？")
                .topK(15)
                .similarityThreshold(0.1)
                .build();

        vectorStore.similaritySearch(req)
                .forEach(doc -> {
                    log.debug("{}, score: {}", doc.getText(), doc.getScore());
                });
    }

    @Test
    void addDocument2() {
        vectorStore.add(List.of(new Document("苹果手机")));
        vectorStore.add(List.of(new Document("苹果正品手机")));
        vectorStore.add(List.of(new Document("iPhone手机")));
        vectorStore.add(List.of(new Document("Apple手机")));
        vectorStore.add(List.of(new Document("iPhone 16 Pro Max 旗舰新品")));
        vectorStore.add(List.of(new Document("小米手机")));
        vectorStore.add(List.of(new Document("OPPO手机")));
        vectorStore.add(List.of(new Document("华为手机")));
    }

    @Test
    void test5() {
        SearchRequest req = SearchRequest.builder()
                .query("苹果 手机")
                .topK(10)
                .similarityThreshold(0.1)
                .build();

        vectorStore.similaritySearch(req)
                .forEach(doc -> {
                    log.debug("{}, score: {}", doc.getText(), doc.getScore());
                });
    }

    @Test
    void test6() {
        // var str = "公司的年假有几天？";
        // var str = "我离职要交接什么？";
        //var str = "可以请几天病假呢？";
        var str = "我有张发票已经搁置40天了，还能报销么？";
        SearchRequest req = SearchRequest.builder()
                .query(str)
                .topK(50)
                .similarityThreshold(0.1)
                .build();

        vectorStore.similaritySearch(req)
                .forEach(doc -> {
                    log.debug("{}, score: {}", doc.getText(), doc.getScore());
                });
    }

    @Test
    void addStudents() {
        var d1 = new Document("""
                {
                "java": "80",
                "sex": "男"
                }
                """, Map.of("userid", "2020212814"));
        var d2 = new Document("""
                {
                "java": "80",
                "sex": "男"
                }
                """);
        var d3 = new Document("""
                {
                "java": "80",,
                "sex": "男"
                }
                """);
        var d4 = new Document("""
                {
                "java": "80",
                "sex": "男"
                }
                """);
        var d5 = new Document("""
                {
                "userid": "2022212818",
                "sex": "男"
                }
                """);
        var g1 = new Document("""
                {
                "java": "80",
                "sex": "女"
                }
                """);
        var g2 = new Document("""
                {
                "java": "80",
                "sex": "女"
                }
                """);
        var g3 = new Document("""
                {
                "java": "80",
                "sex": "女"
                }
                """);
        var g4 = new Document("""
                {
                "java": "80",
                "sex": "女"
                }
                """);
        var g5 = new Document("""
                {
                "java": "80",
                "sex": "女"
                }
                """);

        vectorStore.add(List.of(d1,d2,d3,d4,d5,g1,g2,g3,g4,g5));
    }
}
