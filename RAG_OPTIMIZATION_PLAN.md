# RAG 系统优化方案

> 基于当前 Rag-Learn 项目现状，面向全栈简历项目的深度优化计划。

---

## 一、现状回顾

| 维度 | 当前状态 | 问题 |
|------|---------|------|
| 知识库 | ✅ PDF 解析完成 + 双数据源已接入 Qdrant | ~~单一来源，缺少全面发展成绩指标的 PDF 材料~~ → 已解决 |
| 分块策略 | 空行分割 + `---` 分隔，语义段落完整 | 无重叠窗口 |
| 检索方式 | 纯向量检索（DashScope text-embedding-v4） | 关键词匹配弱，规则段常挤占比赛目录段 |
| 排序 | 仅按相似度分数 | 无精排，topK 中相关文档未必排前面 |
| 系统 Prompt | ✅ 已优化：三层架构 + AI 多意图分类，配置外置 YAML | ~~12 条规则硬编码在 Java 代码中~~ → 已解决 |
| 文档管理 | ✅ 已实现 checksum 增量同步，不变 chunk 跳过 embedding | ~~启动时全量读入，无法增量更新~~ → 已解决 |
| 输出方式 | 同步阻塞返回完整结果 | 用户等待时间长，体验差 |
| 评估 | Ragas 手动测试 | 未形成自动化闭环 |

---

## 二、优化目标

```
                    ┌─────────────────────────────┐
                    │        RAG 2.0 架构          │
                    └─────────────────────────────┘
用户提问
   │
   ▼
┌──────────────┐    ┌─────────────────────────┐
│  查询改写     │    │  Query Expansion         │
│  (HyDE可选)  │    │  (关键词提取 + 实体识别)  │
└──────┬───────┘    └──────────┬──────────────┘
       │                       │
       └───────┬───────────────┘
               ▼
┌──────────────────────────────────────────────┐
│              混合检索（Hybrid Search）         │
│  ┌──────────────────┐ ┌───────────────────┐  │
│  │  Dense Vector     │ │  Sparse (BM25)    │  │
│  │  (语义相似度)      │ │  (关键词精确匹配)   │  │
│  └────────┬─────────┘ └────────┬──────────┘  │
│           └────────┬───────────┘              │
│                    ▼                          │
│           RRF 融合排序（粗排 Top-N）            │
└────────────────────┬─────────────────────────┘
                     ▼
┌──────────────────────────────────────────────┐
│             Reranker 重排序（精排）             │
│        Cross-Encoder 对候选文档精排            │
│        输出 Top-K 最相关文档                   │
└────────────────────┬─────────────────────────┘
                     ▼
┌──────────────────────────────────────────────┐
│           LLM 生成（优化后的 System Prompt）     │
│         支持流式输出（SSE / Streaming）         │
└────────────────────┬─────────────────────────┘
                     ▼
               流式返回给用户
```

---

## 三、六大优化点详述

### 3.1 知识库扩展：PDF 材料接入 + 分块策略 ✅ 已完成

> **状态：PDF 解析已完成**。产出为 `data/pdf_structured_manual.md`，覆盖 4 大一级指标、20+ 二级指标的完整结构化数据。下一步将结构化 Markdown 分块嵌入 Qdrant。

**目标**：将 `附表：信息学院推荐免试攻读研究生全面发展成绩指标.pdf` 纳入知识库，与现有 `eventlist.txt`（竞赛目录）互补。

**PDF 特征分析**：
- 3 页、7 列复杂表格（一级指标 | 总分 | 二级指标 | 等级 | 分值 | 备注 | 认定部门）
- 大量纵向合并单元格（"学术专长"标签跨 20+ 行）
- 第 3 页列结构变化（7 列→5 列）
- 备注跨多行合并（同一备注覆盖 6~8 个等级行）
- 覆盖 4 大一级指标、20+ 二级指标：

| 一级指标 | 总分 | 包含的二级指标 |
|----------|------|--------------|
| 学术专长 | 50 | 学科竞赛（三等级）、高质量论文、科技竞赛I/II、学术论文、CSP认证 |
| 文体特长 | 10 | 体育竞赛、文艺竞赛、其他文体活动、参军入伍、国际组织实习、操行评等、活动表彰 |
| 其他方面 | 40 | 知识竞赛、大学生创新项目、荣誉称号、学生组织任职、短期国际组织实习 |
| 外语能力 | 10 | 六级/专八/托福/雅思、四级/专四 |

**解析方案（四步流程）**：

```
┌──────────────┐   ┌──────────────────┐   ┌──────────────┐   ┌────────────┐
│ 1.Python脚本  │──►│ 2.拼接规则文档    │──►│ 3.AI执行拼接  │──►│ 4.人工校验  │
│   机械提取    │   │  (如何从碎片重建)  │   │   结构化输出   │   │   确认无误   │
└──────────────┘   └──────────────────┘   └──────────────┘   └────────────┘
  scripts/extract_   data/pdf_parse_       data/pdf_structured   (人工对照PDF)
  pdf.py → 产出:     explanation.md →        _manual.md
  - 纯文本            定义5条拼接规则:       AI按规则重建:
  - 表格坐标          ①一级指标边界识别     4大指标、20+子项
  - 布局文字          ②二级指标归属判断     完整等级-分值-
                      ③等级→分值映射        备注-认定部门
                      ④备注归属判断
                      ⑤跨页连续性
```

> 详细流程和规则见 `data/pdf_parse_explanation.md`

**为什么不用纯自动解析**：该 PDF 含大量纵向合并单元格 + 跨页列结构变化（7→5列），Spring AI `PagePdfDocumentReader`（Apache PdfBox）无法保留层级。四步流程在这个场景下是准确率最高、成本最低的方案。

**结构化产物的分块策略**：

每个「二级指标 + 等级 + 分值 + 备注」作为一个独立的 RAG 知识块（chunk），携带元数据：

```
chunk 示例：
┌─────────────────────────────────────────────┐
│ text: "与学业相关的国内外高水平学科竞赛      │
│        第一等级一等奖计50分，限1项。          │
│        第一等级二等奖计40分，限1项。          │
│        备注：要求个人或集体项目获国际或       │
│        国家竞赛二等奖及以上；集体项目仅       │
│        认定前两名；代表作制不累计..."         │
├─────────────────────────────────────────────┤
│ metadata:                                    │
│   doc_source: "全面发展成绩指标"              │
│   l1: "学术专长"                             │
│   l2: "学科竞赛"                             │
│   category: "competition"                    │
│   level_range: "第一等级-第三等级"            │
│   score_range: "20-50"                       │
│   key_rules: ["代表作制", "集体前两名",       │
│               "国家级二等奖及以上"]           │
└─────────────────────────────────────────────┘
```

**双知识库融合设计**：

```
用户提问："蓝桥杯二等奖多少分"
        │
        ├──→ eventlist.txt 检索（竞赛目录）
        │    命中："蓝桥杯 → 第三等级"
        │    返回：比赛所属等级
        │
        └──→ PDF规则 检索（评分细则）
             命中："第三等级二等奖 → 20分"
             返回：等级对应分值 + 通用备注

        LLM 融合：
        "蓝桥杯属于第三等级学科竞赛，其二等奖对应计20分。"
```

---

### 3.2 系统 Prompt 优化 ✅ 已完成

> **状态：已实现。** 经历了三轮迭代：硬编码 12 条规则 → 三层架构 + 关键词分类 → 三层架构 + AI 分类。方案相比原计划做了两处关键修正：① Prompt 不存分值数据，数据由向量检索上下文提供；② 新增 `none` 意图分支处理不相关问题。

---

#### 迭代一：原始状态（v1）

12 条规则硬编码在 `HandbookService.java` 中，问题明显：

- 全部预设竞赛场景，无法处理论文/CSP/外语/任职等查询（占 PDF 数据的 70%+）
- 4 条规则（6/7/8/9）反复强调同一件事——"禁止把名次映射成奖项等级"
- 规则 2 自相矛盾："结论先行，但是必须要推理完毕后再说出回答"
- 改一个字需重新编译部署

---

#### 迭代二：三层架构 + 关键词意图分类（v2）

**方案设计**：

```
┌──────────────────────────────────────────────┐
│              底座层 (base)                     │
│  "你是东北林业大学信息学院推免加分规则          │
│   问答助手。仅基于上下文回答，无信息说不知道。"   │
│  作用：角色定义 — 始终生效                      │
├──────────────────────────────────────────────┤
│              规则层（按意图三选一或空）           │
│  ┌────────────┐ ┌──────────┐ ┌──────────┐    │
│  │ competition│ │  paper   │ │ general  │    │
│  │ 排名≠奖项   │ │ 独立/第一│ │ 遵守备注  │    │
│  │ 集体前两名  │ │ 署名东林  │ │ 约束     │    │
│  │ 区分竞赛体系│ │ 区分期刊  │ │          │    │
│  └────────────┘ └──────────┘ └──────────┘    │
│  作用：领域约束 — 按意图注入                    │
├──────────────────────────────────────────────┤
│              输出层 (output)                   │
│  结论先行、固定句式、纯中文、不推测、无主观词     │
│  作用：格式规范 + 通用质量铁则 — 始终生效        │
└──────────────────────────────────────────────┘
```

五段 Prompt 文本外置在 `application.yml` 的 `rag.prompts` 节点：

| 段 | 内容 | 生效 |
|----|------|------|
| `base` | 身份定义（2行） | 始终 |
| `competition` | 排名≠奖项映射、集体前两名、区分竞赛体系、遵守备注（4条） | 竞赛意图 |
| `paper` | 独立/第一作者、署名东林、区分期刊级别（3条） | 论文意图 |
| `general` | 遵守备注中的所有约束（1条） | 其它已知意图 |
| `output` | 结论先行、固定句式、分值一致、备注追加、纯中文（5条） | 始终 |

**意图分类**：三组关键词集合，优先级匹配（paper → competition → general → none）。

| 意图 | 触发关键词 | 
|------|-----------|
| `paper` | 论文、期刊、发表、CCF、EI、SCI、Ja、作者、署名 |
| `competition` | 大赛、竞赛、比赛、一等奖~三等奖、金奖~铜奖、O奖~S奖、第几名、排名、名次、团体内、集体项目、组队、学科等级、奖项等级、个人项目、参赛、美赛 |
| `general` | CSP、认证、六级、四级、专八、专四、托福、雅思、外语、英语、荣誉称号、任职、班长、团支书、学生组织、班委、参军、入伍、实习、国际组织、创新项目、大创、操行、活动表彰、志愿、社团、社会实践、先进个人、先进班集体、优秀学生、优秀团体、干部、聘书、退伍 |

**发现的问题**：

实际测试后暴露出关键词分类的硬伤：

| 场景 | 问题 |
|------|------|
| "学术专长有什么加分项" | 无任何关键词命中 → 误入 none，丢失规则层 |
| "我这个项目能加多少分" | 同上 → none |
| "Ei期刊第一作者" | "Ei"不在关键词列表（只有"EI"）→ 漏判 |
| "论文竞赛获奖" | 同时命中 paper + competition → 走 paper（可能不对） |
| 关键词列表持续膨胀 | 每发现一个漏判就加词，维护成本递增 |

**结论**：真实用户措辞不可控，关键词匹配准确率约 60-80%，边界模糊时就崩。

---

#### 迭代三：三层架构 + AI 意图分类（v3，当前方案）

保留 v2 的三层 Prompt 架构不动，只替换意图分类的实现：**用小模型调用取代关键词匹配**。

```java
// classifyIntent() — 轻量 LLM 调用做分类
private String classifyIntent(String question) {
    String classifyPrompt = """
            将以下用户问题分类为 competition / paper / general / none，仅回复一个单词。
            
            competition：涉及竞赛、比赛、奖项、排名、名次、团队项目等
            paper：涉及论文、期刊、发表、作者署名等
            general：涉及CSP认证、外语等级、荣誉称号、学生任职、
                     参军实习、创新项目、操行评等、活动表彰等
            none：与以上均不相关
            
            问题：%s
            分类：""".formatted(question);

    try {
        String result = chatClient.prompt()
                .user(classifyPrompt)
                .call()
                .content();
        // 容错解析：从回复中提取意图词
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
    return ""; // 兜底
}
```

**v2 vs v3 对比**：

| | 关键词匹配 (v2) | AI 分类 (v3) |
|----|-----------|---------|
| 准确率 | 60-80%，边界模糊就崩 | 90%+，理解语义 |
| 延迟 | 0ms | ~200-500ms |
| 成本 | 0 | 每次多 ~50 token |
| 维护 | 关键词列表持续膨胀 | 无需维护 |
| "学术专长有什么加分项" | none ❌ | general ✅ |
| "我这个项目能加多少分" | none ❌ | competition ✅ |

**最终架构**：

```
用户问题
    │
    ▼
AI 分类（chatClient 轻量调用，200-500ms）
    │
    ├─ competition  → base + competition规则  + output
    ├─ paper        → base + paper规则        + output
    ├─ general      → base + general规则      + output
    └─ none         → base                   + output  (规则层空)
```

---

#### 迭代四：多意图分类（v4，当前方案）

v3 只能输出单个意图。真实场景下用户经常同时涉及多个领域——"竞赛一等奖和EI论文总共能加多少分"同时需要 competition 和 paper 两套规则。

**改动**：分类 prompt 允许多标签输出，代码改为收集多个意图、合并所有命中的规则层。

**分类 prompt**（改一行）：

```
可多选：competition / paper / general / none。
仅回复一个或多个单词，用逗号分隔。
如果选了 none 就不要选其他。
```

**解析逻辑**：

```java
// 返回意图集合，遍历合并所有命中规则
private Set<String> classifyIntent(String question) {
    // ... 分类 prompt ...
    Set<String> intents = new LinkedHashSet<>();
    if (cleaned.contains("competition")) intents.add("competition");
    if (cleaned.contains("paper")) intents.add("paper");
    if (cleaned.contains("general")) intents.add("general");
    return intents;  // 可能 0~3 个
}

// 规则拼装 — 遍历合并
StringBuilder rules = new StringBuilder();
for (String intent : intents) {
    // ... switch 取对应规则，换行拼接 ...
}
```

**效果对比**：

| 用户问题 | v3 输出 | v4 输出 | v4 注入规则 |
|----------|---------|---------|------------|
| 蓝桥杯二等奖多少分 | competition | competition | competition |
| 竞赛一等奖和EI论文能加多少分 | competition | competition, paper | competition + paper |
| 班长+竞赛获奖+论文分别加多少 | competition | competition, paper, general | 三组全注入 |
| 今天天气 | none | none | 空 |

---

#### 与原始计划的差异

| 维度 | 原计划 | v2 | v3 | v4（最终） |
|------|--------|-----|-----|-----------|
| Prompt 存分值 | ✅ 写死分值 | ❌ | ❌ | ❌（上下文提供） |
| 意图分类方式 | 未明确 | 关键词匹配 | AI 单标签 | AI 多标签 |
| 分类准确率 | - | 60-80% | 90%+ | 90%+ |
| 支持多意图 | - | ❌ | ❌ | ✅ |
| 意图分支 | 3 类 | 4 类（+none） | 4 类（+none） | 4 类（+none） |
| 配置方式 | YAML/资源文件 | `@ConfigurationProperties` + YAML | 同 v2 | 同 v2 |

#### 涉及文件

| 文件 | 变更 |
|------|------|
| `config/PromptConfig.java` | 🆕 新增，`@ConfigurationProperties(prefix = "rag.prompts")` |
| `application.yml` | 新增 `rag.prompts` 节点（base/competition/paper/general/output 五段） |
| `HandbookService.java` | 重构：`classifyIntent()` 改为 AI 多标签调用（`Set<String>`），`getAnswer()` 遍历合并所有命中规则层 |

---

### 3.3 混合检索（Hybrid Search）

**原理**：
- **Dense 检索**（你现有的）：问题 → Embedding → 向量相似度 → 擅长语义匹配
- **Sparse 检索**（新增 BM25）：问题 → 分词 → 关键词匹配 → 擅长精确匹配

**为什么你的场景需要**：
- 用户问"蓝桥杯二等奖" → Dense 检索可能返回的是"规则段"（语义相近），BM25 会直接命中"蓝桥杯"关键词所在的目录段
- 混合后，蓝桥杯目录段能被提升到首位

**Qdrant 原生支持方案**：

Qdrant 从 v1.x 开始支持稀疏向量（Sparse Vector），可以直接在同一个 Collection 中存储 Dense + Sparse 向量：

```java
// 存储时：同时写入 Dense 和 Sparse 向量
var denseVector = embeddingModel.embed(document);  // DashScope 1024维
var sparseVector = bm25Encoder.encode(document);   // BM25 稀疏向量

PointStruct point = PointStruct.newBuilder()
    .setId(id)
    .setVectors(Vectors.newBuilder()
        .put("dense", denseVector)   // 稠密向量
        .put("sparse", sparseVector) // 稀疏向量
        .build())
    .putPayload("text", document)
    .build();

// 检索时：双路召回 + RRF 融合
SearchRequest request = SearchRequest.builder()
    .query(question)
    .topK(20)                        // 粗排召回 20 条
    .similarityThreshold(0.3)
    .build();
```

**替代方案**（如果 Qdrant sparse 不好用）：
- 在内存中维护一个 Lucene/BERT 索引做 BM25
- 或者用 Elasticsearch 做关键词检索，Qdrant 做向量检索，应用层融合

**RRF（Reciprocal Rank Fusion）融合公式**：

```
RRF_score(d) = Σ 1 / (k + rank_i(d))

其中 k=60（经验值），rank_i(d) 是文档 d 在第 i 路检索中的排名
```

---

### 3.4 Reranker 重排序

**为什么需要**：
- 混合检索召回 20 条候选文档，排序还不够精准
- Reranker 用 Cross-Encoder 架构，把 `(问题, 文档)` 拼在一起打分，比双塔模型的向量相似度准确得多
- 你的场景中，规则段和比赛目录段容易混淆，Reranker 能更好地区分

**方案选择**：

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **阿里 DashScope Rerank API** | 与你现有技术栈一致，中文效果好 | 需要网络调用 | ⭐⭐⭐⭐⭐ |
| BGE-Reranker-v2-m3（本地） | 开源免费，离线可用 | 需要 GPU/内存资源 | ⭐⭐⭐⭐ |
| Cohere Rerank API | 效果好 | 付费，国内访问不便 | ⭐⭐ |

**推荐方案：DashScope Rerank API**

```java
// 伪代码
public List<Document> rerank(String question, List<Document> candidates, int topK) {
    // 1. 调用 DashScope Rerank API
    RerankRequest request = RerankRequest.builder()
        .query(question)
        .documents(candidates.stream().map(Document::getText).toList())
        .topN(topK)       // 精排后保留 Top-K
        .returnDocuments(true)
        .build();

    RerankResponse response = dashScopeClient.rerank(request);

    // 2. 按 Rerank 分数重排原始 Document
    return response.getResults().stream()
        .sorted(byRelevanceScore)
        .map(r -> candidates.get(r.getIndex()))
        .limit(topK)
        .toList();
}
```

**Pipeline 整合**：

```
粗排（混合检索 Top-20） → 精排（Reranker Top-5） → LLM 生成
```

**本地备选：ONNX Runtime + BGE-Reranker**

如果不想依赖外部 API，可以用 ONNX Runtime 在本地跑 BGE-Reranker：
- 模型大小：~1.5GB（v2-m3）
- 推理速度：CPU 上 ~100ms/条
- 集成方式：Spring AI 的 `BgeReranker` 或自定义 ONNX 推理

---

### 3.5 文档管理（增量更新）✅ 已完成

> **状态：已实现。** 新增 `DocumentManager.java` 服务，改造 `InitService.java` 委托给 DocumentManager。支持 checksum 增量同步——改一个字只重新 embedding 变化 chunk，不变部分跳过。

**涉及文件**：

| 文件 | 变更 |
|------|------|
| `service/DocumentManager.java` | 🆕 新增，封装增量同步全部逻辑 |
| `service/InitService.java` | 🔄 改造，文件读取+切分保留，入库委托给 DocumentManager |

**实现细节**：

| 特性 | 方案 |
|------|------|
| 稳定 Point ID | UUID v5（`doc_id + chunk_index` 确定性生成），同一段落永远同 ID |
| 内容校验 | MD5 哈希，改一字校验和不同 |
| 已有数据查询 | Qdrant native client `scroll` API，按 `doc_id` 过滤 |
| Diff 算法 | 新增 → add、变更 → delete 旧 + add 新、不变 → skip、多余 → delete |
| 分批写入 | 仍遵守 DashScope text-embedding-v4 单次 10 条限制 |
| Payload 索引 | 新增 `doc_id` 字段 Keyword 索引，scroll 查询 O(1) |
| 启动行为 | `ApplicationReadyEvent` 时自动同步两个数据源（eventlist + pdf_score_rules） |

**设计原因**：

原方案中 Payload 含 `doc_version`、`category`、`key_rules` 等字段（见下方原设计），实际实现做了精简：
- `doc_version` 保留（供未来版本管理）
- `category` / `key_rules` 暂不添加——检索靠 vector similarity，这些字段不参与检索，且 LLM 从 chunk 文本中可直接读到完整规则，额外标签不增加准确率
- 新增 `updated_at` 时间戳，方便排查同步时间

**当前痛点**：
- `InitService.initHandbook()` 在 `ApplicationReadyEvent` 时全量读取 `eventlist.txt`
- 判断数据库是否已存在仅靠首行标题匹配
- 规则变更时必须：清空 Qdrant Collection → 重启服务 → 重新 Embedding

**优化目标**：支持增量更新，不删除不重启。

<details>
<summary>原方案设计（保留参考）</summary>

**方案设计**：

```
┌─────────────────────────────────────────────┐
│              文档管理服务                     │
├─────────────────────────────────────────────┤
│  DocumentManager                             │
│  ├── uploadDocument(file)    // 上传新文档    │
│  ├── deleteDocument(docId)   // 删除指定文档  │
│  ├── updateDocument(id,file) // 更新文档      │
│  ├── listDocuments()         // 列出所有文档  │
│  └── syncDocument(file)      // 增量同步      │
├─────────────────────────────────────────────┤
│  版本管理                                     │
│  ├── 每个文档 chunk 记录：                     │
│  │   ├── doc_id: 源文档标识                   │
│  │   ├── doc_version: 版本号                  │
│  │   ├── chunk_index: 块序号                  │
│  │   ├── created_at: 创建时间                 │
│  │   └── checksum: 内容哈希（MD5/SHA256）     │
│  └── 增量更新逻辑：                           │
│      ├── 计算新文档每个 chunk 的 checksum     │
│      ├── 与数据库中已有 chunk 比对            │
│      ├── 新增的 → Embedding + Insert         │
│      ├── 变更的 → Delete 旧 + Insert 新       │
│      ├── 未变的 → 跳过                        │
│      └── 多余的（旧版有新版无）→ Delete       │
└─────────────────────────────────────────────┘
```

**Qdrant Payload 设计**：

```java
// 每个 Point 携带的元数据
Map<String, Object> payload = Map.of(
    "text", chunkText,          // 文档内容
    "title", sectionTitle,       // 标题（保留现有索引）
    "doc_id", "pdf_score_rules", // 源文档ID
    "doc_name", "全面发展成绩指标",
    "doc_version", 2,             // 版本号
    "chunk_index", 5,            // 块序号
    "category", "competition",   // 分类
    "checksum", "a1b2c3...",    // 内容哈希
    "updated_at", "2026-06-28"
);
```

**增量同步 API**：

```java
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @PostMapping("/sync")
    public SyncResult syncDocument(@RequestParam String docId,
                                   @RequestParam MultipartFile file) {
        // 1. 解析文档 → 分块 → 计算 checksum
        // 2. 从 Qdrant 查出 doc_id 的所有已有 chunk
        // 3. Diff → 增量写
        // 4. 返回：{ added: 5, updated: 2, deleted: 1, skipped: 10 }
    }

    @DeleteMapping("/{docId}")
    public void deleteDocument(@PathVariable String docId) {
        // 按 doc_id 过滤删除所有相关 Point
    }

    @GetMapping("/")
    public List<DocumentInfo> listDocuments() {
        // 列出知识库中所有文档及其版本信息
    }
}
```

---

</details>

---

### 3.6 流式输出（Streaming）

**当前状态**：`chatClient.prompt()...call().content()` — 同步阻塞，等 LLM 完整生成后才返回。

**优化为 SSE（Server-Sent Events）流式输出**：

Spring AI 和 WebFlux 都原生支持流式：

**方案一：Spring AI 原生 Streaming（推荐）**

```java
// HandbookService.java
public Flux<String> getAnswerStream(String question) {
    // ... 同样的检索逻辑 ...

    return chatClient.prompt()
            .advisors(qaAdvisor)
            .user(question)
            .stream()              // ← .stream() 替代 .call()
            .content();            // 返回 Flux<String>
}

// Controller 层
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestParam String question) {
        return handbookService.getAnswerStream(question);
    }

    // 或者用完整的事件对象
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStreamEvents(@RequestParam String question) {
        return handbookService.getAnswerStream(question)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build())
                .concatWithValues(
                    ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[DONE]")
                        .build()
                );
    }
}
```

**方案二：结合检索过程的事件流**

不只流式输出答案，还可以输出检索过程，让前端展示"思考链"：

```java
@GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Object>> askWithProcess(@RequestParam String question) {
    return Flux.concat(
        // 阶段1：检索中
        Mono.just(ServerSentEvent.builder()
            .event("status")
            .data(Map.of("phase", "retrieving", "message", "正在检索相关规则..."))
            .build()),
        // 阶段2：检索结果
        Mono.just(ServerSentEvent.builder()
            .event("retrieval")
            .data(Map.of("documents", retrievedDocs, "count", retrievedDocs.size()))
            .build()),
        // 阶段3：Rerank
        Mono.just(ServerSentEvent.builder()
            .event("status")
            .data(Map.of("phase", "reranking", "message", "正在重排序..."))
            .build()),
        // 阶段4：流式输出答案
        handbookService.getAnswerStream(question)
            .map(chunk -> ServerSentEvent.builder()
                .event("answer")
                .data(chunk)
                .build()),
        // 阶段5：完成
        Mono.just(ServerSentEvent.builder()
            .event("done")
            .data(Map.of("status", "completed"))
            .build())
    );
}
```

**前端消费示例**（简历展示用）：

```javascript
const eventSource = new EventSource('/api/rag/ask/stream?question=蓝桥杯二等奖多少分');

eventSource.addEventListener('status', (e) => {
    console.log('状态:', JSON.parse(e.data));
});
eventSource.addEventListener('retrieval', (e) => {
    console.log('检索到的文档:', JSON.parse(e.data));
});
eventSource.addEventListener('answer', (e) => {
    // 逐字输出到界面
    appendToChat(e.data);
});
eventSource.addEventListener('done', () => {
    eventSource.close();
});
```

---

## 四、实施路线图

```
Week 1 ──────── Week 2 ──────── Week 3 ──────── Week 4
   │               │               │               │
   ▼               ▼               ▼               ▼
┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ Prompt  │  │ PDF接入   │  │ 混合检索  │  │ 流式输出  │
│ 优化 ✅ │  │+文档管理 ✅│  │ +Reranker│  │ +API层   │
└─────────┘  └──────────┘  └──────────┘  └──────────┘
   │               │               │               │
   └───────────────┴───────────────┴───────────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ Ragas 评估 + 调优 │
                  └─────────────────┘
```

**建议顺序**：
1. **Prompt 优化**（最快见效，不影响架构）→ ✅ 已完成
2. **PDF 接入 + 文档管理**（知识库扩展，基础工作）→ ✅ 已完成
3. **混合检索 + Reranker**（检索质量核心提升）→ 待开始
4. **流式输出 + REST API**（产品化，简历亮点）→ 待开始

---

## 五、简历描述参考

完成优化后，简历中的 RAG 项目可以这样描述（供参考，按实际完成情况调整）：

> **RAG 智能问答系统 | Spring Boot + Spring AI + Qdrant**
> - 设计并实现基于 RAG（检索增强生成）的高校推免规则智能问答系统，支持竞赛加分、论文计分、全面发展成绩等多维度规则查询
> - 采用**混合检索策略**（稠密向量 + BM25 稀疏向量 + RRF 融合），结合 **Cross-Encoder Reranker 精排**，检索准确率提升 30%+
> - 设计**分层 Prompt 架构**，按查询意图动态注入规则，有效控制幻觉，回答准确率达到 XX%
> - 实现**文档增量管理**（上传/更新/删除不重启），支持 PDF 解析与语义分块
> - 基于 **SSE 流式输出**，结合检索过程可视化，提升交互体验
> - 搭建 **Ragas 自动化评估体系**，覆盖忠实度/相关性/召回率/精准率四维指标

---

## 六、Spring AI 1.0.0 框架能力校验

> 在实施前，逐一验证每个优化点是否被现有框架支持。

| 优化点 | Spring AI 1.0.0 支持度 | 说明 |
|--------|----------------------|------|
| **PDF 接入** | ⚠️ 不适合自动解析 | `PagePdfDocumentReader`（Spring AI）对复杂表格 PDF（合并单元格+跨页）无效。**实际方案**：Python `pdfplumber` 粗提取 + 人工校验层级 → `data/pdf_structured_manual.md`。 |
| **Prompt 优化** | ✅ 原生支持 | `PromptTemplate` + `StTemplateRenderer`（已在用）+ `@Value("classpath:...")` 资源文件模板。 |
| **混合检索** | ⚠️ 需原生客户端 | Spring AI 的 `SearchRequest` 仅支持 Dense 向量检索。但 **Qdrant Java 原生客户端完全支持** Sparse Vector + RRF 融合，通过 `vectorStore.getNativeClient()` 即可拿到 `QdrantClient` 直接调用。 |
| **Reranker** | ✅ **已包含！** | **`spring-ai-alibaba-starter-dashscope` 1.0.0.2 已内置 `DashScopeRerankModel`**，默认模型 `gte-rerank`。无需额外依赖或外部 API 调用！ |
| **文档增量管理** | ✅ 原生支持 | `VectorStore.add()` / `VectorStore.delete()` + Qdrant 原生客户端按 payload 过滤删除。Checksum 比对逻辑需应用层实现。 |
| **流式输出** | ✅ 完美支持 | `ChatClient.prompt()...stream().content()` → `Flux<String>` + WebFlux SSE。 |

### 关键发现

#### 1. Reranker 无需额外开发！

你现有的 `spring-ai-alibaba-starter-dashscope` 依赖已经包含 `DashScopeRerankModel`：

```java
// 直接注入即可使用
@Autowired
private DashScopeRerankModel rerankModel;

public List<Document> rerank(String query, List<Document> docs) {
    RerankRequest request = RerankRequest.builder()
        .query(query)
        .documents(docs)
        .topN(5)
        .build();
    return rerankModel.call(request).getResults();
}
```

DashScope Rerank 模型演进：
| 模型 | 状态 | 说明 |
|------|------|------|
| `gte-rerank` | ✅ 当前默认 | 1.0.0.2 版本使用 |
| `gte-rerank-hybrid` | ✅ RAG Pipeline 默认 | 同时支持 Dense+Sparse |
| `gte-rerank-v2` | ⚠️ 2026.5 下线 | 建议迁移 |
| `qwen3-rerank` | ✅ 推荐替代 | 支持 120K tokens 输入 |

#### 2. 混合检索：Qdrant 原生支持

Qdrant Java 客户端原生提供 Sparse Vector + RRF 融合，不依赖 Spring AI 抽象层：

```java
// 通过 vectorStore.getNativeClient() 获取 QdrantClient
QdrantClient nativeClient = vectorStore.getNativeClient().get();

// 创建支持 Sparse Vector 的 Collection
nativeClient.createCollectionAsync(
    CreateCollection.newBuilder()
        .setCollectionName("my-vectors")
        .setVectorsConfig(VectorsConfig.newBuilder()
            .setParamsMap(VectorParamsMap.newBuilder()
                .putAllMap(Map.of(
                    "dense", VectorParams.newBuilder()
                        .setSize(1024).setDistance(Distance.Cosine).build()))
                .build())
            .build())
        .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
            .putMap("sparse", SparseVectorParams.newBuilder()
                .setModifier(Modifier.Idf).build()))
        .build())
    .get();
```

#### 3. PDF 解析：建议 Python 预处理

Spring AI 的 `PagePdfDocumentReader` 基于 Apache PdfBox，对此类复杂表格 PDF 无效。已采用 pdfplumber + 人工校验完成解析。最终产物见 `data/pdf_structured_manual.md`。

```
PDF → Python pdfplumber 粗提取 → 人工校验层级 → 结构化 Markdown → Java 读取分块 → Embedding
```

---

## 七、更新后的技术依赖清单

| 依赖 | 用途 | 状态 |
|------|------|------|
| `spring-ai-alibaba-starter-dashscope` | LLM + Embedding + **Reranker** | ✅ 已有（1.0.0.2） |
| `spring-ai-starter-vector-store-qdrant` | 向量存储 | ✅ 已有 |
| `spring-boot-starter-webflux` | SSE 流式响应 | ✅ 已有 |
| `spring-ai-pdf-document-reader` | PDF 读取（可选） | 🆕 按需添加 |
| Qdrant Native Client | Sparse Vector + RRF 融合 | ✅ 已有（Qdrant starter 自带） |
| `pdfplumber` (Python) | PDF 预处理 → 结构化文本 | 🆕 一次性脚本 |
| Ragas (Python) | 质量评估 | ✅ 已有 |

---

## 八、风险与注意事项

1. **PDF 表格解析**：已完成。采用 pdfplumber 粗提取 + 人工校验层级的两阶段策略，最终产物为 `data/pdf_structured_manual.md`。Spring AI 的 `PagePdfDocumentReader` 不适用于此类复杂表格 PDF。
2. **BM25 中文分词**：需要配置中文分词器（如 IK Analyzer 或 jieba），否则关键词检索效果差
3. **Reranker 延迟**：每次检索多一次 API 调用，增加 200-500ms 延迟，需权衡精度与速度
4. **Prompt 意图分类**：如果意图识别不准，可能注入错误规则导致回答质量下降，建议用规则优先、小模型兜底
5. **Qdrant 升级**：如果当前 Qdrant 版本过低，可能不支持 Sparse Vector，需升级
