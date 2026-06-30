package org.example.raglearn.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档增量管理服务。
 *
 * 核心职责：
 * 1. 为每个 chunk 生成稳定 ID（UUID v5）和内容校验和（MD5）
 * 2. 与 Qdrant 中已有 chunk 做 diff 比对
 * 3. 只对新增/变更的 chunk 调用 embedding，不变的跳过
 * 4. 删除新版中不存在的旧 chunk
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentManager {

    private static final int DASHSCOPE_BATCH_LIMIT = 10;
    private static final String COLLECTION_NAME = "my-vectors";

    private final VectorStore vectorStore;

    // ════════════════════ 增量同步 ════════════════════

    /**
     * 将一个文档的所有 chunk 增量同步到 Qdrant。
     *
     * @param docId      源文档唯一标识（如 "eventlist" / "pdf_score_rules"）
     * @param docName    文档可读名称
     * @param docVersion 版本号（人工递增或时间戳）
     * @param chunks     本次解析出的所有 chunk
     * @return 同步结果（added / updated / deleted / skipped 各多少）
     */
    public SyncResult syncDocument(String docId, String docName, int docVersion,
                                   List<Document> chunks) {
        log.info("[{}] 开始增量同步，共 {} 个 chunk，版本 {}", docId, chunks.size(), docVersion);

        // 1. 为每个 chunk 设置稳定 ID + 元数据 + 计算校验和
        Map<Integer, String> newChecksums = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document doc = chunks.get(i);
            String checksum = computeMD5(doc.getText());
            newChecksums.put(i, checksum);

            // 稳定 ID：同一 doc_id + chunk_index → 同一 UUID（v5 确定性生成）
            String stableId = UUID.nameUUIDFromBytes(
                    (docId + "_" + i).getBytes(StandardCharsets.UTF_8)).toString();
            doc.setId(stableId);

            // 将元数据注入 Document（保留已有的 title）
            Map<String, Object> meta = new HashMap<>(doc.getMetadata());
            meta.put("doc_id", docId);
            meta.put("doc_name", docName);
            meta.put("doc_version", docVersion);
            meta.put("chunk_index", i);
            meta.put("checksum", checksum);
            meta.put("updated_at", LocalDateTime.now().toString());
            // 用新的 metadata map 替换
            chunks.set(i, new Document(doc.getId(), doc.getText(), meta));
        }

        // 2. 从 Qdrant 查出该 doc_id 下已有的所有 chunk
        Map<Integer, ExistingChunk> existing = scrollExistingChunks(docId);
        log.info("[{}] Qdrant 中已有 {} 个 chunk", docId, existing.size());

        // 3. Diff 比对
        List<Document> toAdd = new ArrayList<>();
        List<String> toDeleteIds = new ArrayList<>();
        int added = 0, updated = 0, skipped = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String newChecksum = newChecksums.get(i);
            ExistingChunk old = existing.get(i);

            if (old == null) {
                // 新 chunk：旧版不存在
                toAdd.add(chunks.get(i));
                added++;
            } else if (!old.checksum.equals(newChecksum)) {
                // 变更的 chunk：删旧 + 加新
                toDeleteIds.add(old.pointUuid);
                toAdd.add(chunks.get(i));
                updated++;
            } else {
                // 未变：跳过
                skipped++;
            }
        }

        // 多余的：旧版有但新版无
        int deleted = 0;
        for (Map.Entry<Integer, ExistingChunk> entry : existing.entrySet()) {
            if (!newChecksums.containsKey(entry.getKey())) {
                toDeleteIds.add(entry.getValue().pointUuid);
                deleted++;
            }
        }

        // 4. 执行删除（先删旧，再加新）
        if (!toDeleteIds.isEmpty()) {
            deletePoints(toDeleteIds);
            log.info("[{}] 已删除 {} 个旧 Point", docId, toDeleteIds.size());
        }

        // 5. 分批写入（遵守 DashScope 10 条限制）
        if (!toAdd.isEmpty()) {
            List<List<Document>> batches = splitIntoBatches(toAdd, DASHSCOPE_BATCH_LIMIT);
            for (int j = 0; j < batches.size(); j++) {
                List<Document> batch = batches.get(j);
                log.info("[{}] embedding 第 {}/{} 批，数量：{}",
                        docId, j + 1, batches.size(), batch.size());
                vectorStore.add(batch);
            }
        }

        SyncResult result = new SyncResult(added, updated, deleted, skipped);
        log.info("[{}] 同步完成 → {}", docId, result);
        return result;
    }

    // ════════════════════ 删除 + 查询 ════════════════════

    /**
     * 按 doc_id 删除该文档的所有 chunk。
     */
    public void deleteDocument(String docId) {
        Optional<QdrantClient> nativeClient = vectorStore.getNativeClient();
        if (nativeClient.isEmpty()) {
            log.warn("无法获取 Qdrant 原生客户端，删除操作跳过");
            return;
        }
        try {
            Points.DeletePoints request = Points.DeletePoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setPoints(Points.PointsSelector.newBuilder()
                            .setFilter(Points.Filter.newBuilder()
                                    .addMust(buildDocIdCondition(docId))
                                    .build())
                            .build())
                    .setWait(true)
                    .build();
            nativeClient.get().deleteAsync(request).get();
            log.info("[{}] 已删除所有相关 chunk", docId);
        } catch (Exception e) {
            log.error("[{}] 删除失败: {}", docId, e.getMessage(), e);
        }
    }

    // ════════════════════ Payload 索引 ════════════════════

    /**
     * 确保 Qdrant 上存在必要的 Payload 索引。
     * title：供 HandbookService filter 使用
     * doc_id：供本服务 scroll 过滤使用
     */
    public void ensureIndexes() {
        Optional<QdrantClient> nativeClient = vectorStore.getNativeClient();
        if (nativeClient.isEmpty()) {
            return;
        }
        for (String field : new String[]{"title", "doc_id"}) {
            try {
                nativeClient.get()
                        .createPayloadIndexAsync(
                                COLLECTION_NAME,
                                field,
                                Collections.PayloadSchemaType.Keyword,
                                null, true, null, null)
                        .get();
                log.debug("Payload 索引 {} 已就绪", field);
            } catch (Exception e) {
                log.debug("Payload 索引 {} 可能已存在: {}", field, e.getMessage());
            }
        }
    }

    // ════════════════════ 私有方法 ════════════════════

    /**
     * 计算文本的 MD5 校验和（16 进制字符串）。
     */
    private String computeMD5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }

    /**
     * 从 Qdrant 查出指定 doc_id 下的所有 chunk。
     *
     * @return Map<chunkIndex, ExistingChunk>
     */
    private Map<Integer, ExistingChunk> scrollExistingChunks(String docId) {
        Map<Integer, ExistingChunk> result = new HashMap<>();
        Optional<QdrantClient> nativeClient = vectorStore.getNativeClient();
        if (nativeClient.isEmpty()) {
            return result;
        }
        try {
            Points.Filter filter = Points.Filter.newBuilder()
                    .addMust(buildDocIdCondition(docId))
                    .build();

            Points.ScrollPoints request = Points.ScrollPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setFilter(filter)
                    .setLimit(1000)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder()
                            .setEnable(true)
                            .build())
                    .build();

            Points.ScrollResponse response = nativeClient.get().scrollAsync(request).get();

            for (Points.ScoredPoint point : response.getResultList()) {
                String pointUuid = point.getId().getUuid();
                Map<String, com.google.protobuf.Value> payload = point.getPayloadMap();

                // 提取 chunk_index 和 checksum
                int chunkIndex = (int) payload.getOrDefault("chunk_index",
                        com.google.protobuf.Value.newBuilder().setIntegerValue(-1).build())
                        .getIntegerValue();
                String checksum = payload.getOrDefault("checksum",
                        com.google.protobuf.Value.newBuilder().setStringValue("").build())
                        .getStringValue();

                if (chunkIndex >= 0) {
                    result.put(chunkIndex, new ExistingChunk(pointUuid, checksum, chunkIndex));
                }
            }
        } catch (Exception e) {
            log.warn("[{}] 查询已有 chunk 失败（可能首次同步）: {}", docId, e.getMessage());
        }
        return result;
    }

    /**
     * 按 Point UUID 列表批量删除。
     */
    private void deletePoints(List<String> pointUuids) {
        if (pointUuids.isEmpty()) {
            return;
        }
        Optional<QdrantClient> nativeClient = vectorStore.getNativeClient();
        if (nativeClient.isEmpty()) {
            return;
        }
        try {
            List<Points.PointId> pointIds = pointUuids.stream()
                    .map(uuid -> Points.PointId.newBuilder().setUuid(uuid).build())
                    .toList();

            Points.DeletePoints request = Points.DeletePoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setPoints(Points.PointsSelector.newBuilder()
                            .setPoints(Points.PointsIdsList.newBuilder()
                                    .addAllIds(pointIds)
                                    .build())
                            .build())
                    .setWait(true)
                    .build();

            nativeClient.get().deleteAsync(request).get();
        } catch (Exception e) {
            log.error("批量删除 Point 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 构建 doc_id == value 的过滤条件。
     */
    private Points.Condition buildDocIdCondition(String docId) {
        return Points.Condition.newBuilder()
                .setField(Points.FieldCondition.newBuilder()
                        .setKey("doc_id")
                        .setMatch(Points.Match.newBuilder()
                                .setKeyword(docId)
                                .build())
                        .build())
                .build();
    }

    /**
     * 将 Document 列表按 batchSize 切分。
     */
    private List<List<Document>> splitIntoBatches(List<Document> documents, int batchSize) {
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, documents.size());
            batches.add(new ArrayList<>(documents.subList(i, endIndex)));
        }
        return batches;
    }

    // ════════════════════ 内部类型 ════════════════════

    /**
     * 增量同步结果。
     */
    public record SyncResult(int added, int updated, int deleted, int skipped) {
        @Override
        public String toString() {
            return String.format("added=%d, updated=%d, deleted=%d, skipped=%d",
                    added, updated, deleted, skipped);
        }
    }

    /**
     * Qdrant 中已有的 chunk 信息。
     */
    private record ExistingChunk(String pointUuid, String checksum, int chunkIndex) {
    }
}
