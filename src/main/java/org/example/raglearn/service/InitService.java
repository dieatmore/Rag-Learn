package org.example.raglearn.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动时自动从 eventlist.txt / pdf_structured_manual.md 读取并分块，
 * 委托 {@link DocumentManager} 做 checksum 增量同步入库。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InitService {

    private final DocumentManager documentManager;

    @Value("classpath:eventlist.txt")
    private Resource txtSource;

    @Value("file:data/pdf_structured_manual.md")
    private Resource manualSource;

    /**
     * Spring 容器启动完成后自动执行。
     * 先建索引，再分别增量同步两个数据源。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initHandbook() throws Exception {
        documentManager.ensureIndexes();
        initEventList();
        initStructuredManual();
    }

    // ════════════════════ eventlist.txt ════════════════════

    /**
     * 读取 eventlist.txt（竞赛目录），按空行切分为独立段落。
     * 每段第一行作为 title。
     */
    private void initEventList() throws Exception {
        String script = txtSource.getContentAsString(Charset.defaultCharset());

        // 按空行切分
        String[] split = script.split("\\r?\\n\\s*\\r?\\n");
        List<Document> docs = new ArrayList<>();
        for (String info : split) {
            var title = info.split("\\R", 2)[0];
            var doc = new Document(info, Map.of("title", title));
            docs.add(doc);
        }

        log.info("eventlist.txt 解析完成，共 {} 个段落", docs.size());
        documentManager.syncDocument("eventlist", "竞赛目录", 1, docs);
    }

    // ════════════════════ pdf_structured_manual.md ════════════════════

    /**
     * 读取 pdf_structured_manual.md（推免加分规则全文），按 --- 分隔符切分。
     * 跳过文件头 block，每个后续 block 取第一个 ## 或 ### 行作为 title。
     */
    private void initStructuredManual() throws Exception {
        String content = manualSource.getContentAsString(Charset.defaultCharset());

        // 按 markdown 水平线 --- 切分
        String[] sections = content.split("\\n---\\n");

        List<Document> docs = new ArrayList<>();
        // i=0 是文件头（标题 + "解析方式" 说明），跳过
        for (int i = 1; i < sections.length; i++) {
            String section = sections[i].trim();
            if (section.isBlank()) {
                continue;
            }
            // 提取第一个 ## 或 ### 行作为 title
            String title = section.lines()
                    .filter(line -> line.startsWith("## ") || line.startsWith("### "))
                    .findFirst()
                    .map(line -> line.replaceFirst("^#+\\s*", "").trim())
                    .orElse("未命名段落-" + i);
            docs.add(new Document(section, Map.of("title", title)));
        }

        if (docs.isEmpty()) {
            log.warn("pdf_structured_manual.md 未解析出任何段落，跳过入库");
            return;
        }

        log.info("pdf_structured_manual.md 解析完成，共 {} 个段落", docs.size());
        documentManager.syncDocument("pdf_score_rules", "全面发展成绩指标", 1, docs);
    }
}
