package org.example.raglearn.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 分层 Prompt 配置，从 application.yml 的 rag.prompts 节点读取。
 *
 * <pre>
 * 三层拼装：
 *   base（底座-身份）                → 始终生效
 *   competition | paper | general    → 按意图三选一注入
 *   output（输出格式+通用铁则）        → 始终生效
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "rag.prompts")
@Data
public class PromptConfig {

    /** 底座 — 身份定义 */
    private String base;

    /** 竞赛规则 — 赛事/奖项/排名/集体项目 关键词触发 */
    private String competition;

    /** 论文规则 — 期刊/作者/署名 关键词触发 */
    private String paper;

    /** 通用规则 — 兜底，CSP/外语/任职/荣誉/参军/实习等 */
    private String general;

    /** 输出格式 + 通用铁则（分值一致/纯中文/不推测） */
    private String output;
}
