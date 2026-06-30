# RAG 推免规则智能问答系统

基于 RAG（检索增强生成）的东北林业大学信息学院推免加分规则问答系统。支持竞赛、论文、荣誉称号、CSP 认证、外语能力等多维度加分查询。

## 技术栈

| 层 | 技术 |
|---|------|
| 框架 | Spring Boot 3.x + Spring AI 1.0.0 |
| LLM | 阿里 DashScope（通义千问） |
| Embedding | DashScope text-embedding-v4（1024 维） |
| 向量库 | Qdrant |
| 评估 | Ragas（Python 脚本） |
| 构建 | Maven + Java 17 |

## 项目结构

```
src/main/java/org/example/raglearn/
├── RagLearnApplication.java
├── config/
│   ├── ChatClientConfig.java      # ChatClient Bean
│   ├── PromptConfig.java          # 分层 Prompt 配置（YAML → Java）
│   ├── RestClientConfig.java
│   └── RestTemplateConfig.java
└── service/
    ├── InitService.java           # 启动时读取文件 + 切分段落
    ├── DocumentManager.java       # 文档增量管理（checksum diff → 增量入库）
    ├── HandbookService.java       # RAG 问答核心
    └── RagasEvaluationClient.java # Ragas 评估 HTTP 客户端

data/
├── eventlist.txt                  # 竞赛目录（学科竞赛三等级）
├── pdf_structured_manual.md       # PDF 结构化解析结果
└── pdf_parse_explanation.md       # PDF 解析规则说明

scripts/
└── extract_pdf.py                 # pdfplumber 粗提取脚本
```

## RAG Pipeline

```
                         ┌─────────────────────────┐
                         │  知识库管理（启动时自动）  │
                         │  InitService → 文件读取   │
                         │     ↓ 切分段落            │
                         │  DocumentManager         │
                         │     ↓ MD5 校验和          │
                         │     ↓ Qdrant scroll 查已有 │
                         │     ↓ diff 比对            │
                         │     ↓ 只 embed 变化部分    │
                         └─────────────────────────┘

用户提问
    │
    ▼
意图分类（AI 多标签：competition / paper / general / none）
    │
    ▼
向量检索（Qdrant Dense Retrieval，topK=5，threshold=0.4）
    │
    ▼
分层 Prompt 拼装（base + 规则层 + output）
    │
    ▼
LLM 生成（DashScope 通义千问）
    │
    ▼
同步返回
```

## 文档增量管理

启动时 `InitService` 读取两个数据源并切分为段落，由 `DocumentManager` 负责增量同步：

| 步骤 | 说明 |
|------|------|
| 稳定 ID | UUID v5（doc_id + chunk_index 确定性生成），同一段落永远同 ID |
| 校验和 | 每个 chunk 计算 MD5 哈希 |
| Qdrant scroll | 按 `doc_id` 过滤查出已有 chunk |
| Diff 比对 | 新增 → add、变更 → delete 旧 + add 新、不变 → skip、多余 → delete |
| 分批写入 | 遵守 DashScope text-embedding-v4 单次 10 条限制 |

**效果**：改一个字、加一段规则、删一段，重启后只重新 embedding 变化的部分，其余 chunk 0 次 API 调用。

## 分层 Prompt 架构

| 层 | 内容 | 生效条件 |
|----|------|---------|
| base | 角色定义 | 始终 |
| competition | 排名≠奖项映射、集体前两名、区分竞赛体系 | AI 判定为竞赛意图 |
| paper | 独立/第一作者、署名东林、区分期刊级别 | AI 判定为论文意图 |
| general | 备注约束（代表作制/限项/上限/时间） | AI 判定为其他已知意图 |
| output | 结论先行、固定句式、纯中文、不推测 | 始终 |

Prompt 文本存储在 `application.yml` 的 `rag.prompts` 节点，修改无需重新编译。

## 迭代历史

### 一、PDF 规则材料接入

**背景**：知识库仅有 `eventlist.txt`（竞赛目录），缺少推免加分评分细则。原始材料是一份 3 页 PDF 表格——`附表：信息学院推荐免试攻读研究生全面发展成绩指标.pdf`，覆盖 4 大一级指标、20+ 二级指标的完整评分体系。

**困难**：PDF 表格含大量纵向合并单元格（"学术专长"跨 20+ 行）、第 3 页列结构从 7 列变为 5 列、备注跨多行合并（同一备注覆盖 6~8 个等级行）。Spring AI 的 `PagePdfDocumentReader`（Apache PdfBox）无法保留表格层级。

**方案**：四步流程。

1. **Python 脚本机械提取**：`scripts/extract_pdf.py` 用 pdfplumber 提取纯文本、表格坐标、布局文字，不做语义理解
2. **编写拼接规则**：`data/pdf_parse_explanation.md` 定义 5 条规则——一级指标边界识别、二级指标归属判断、等级→分值映射、备注归属判断、跨页连续性
3. **AI 按规则重建**：将碎片文本 + 规则交给 LLM，输出结构化 Markdown
4. **人工校验**：对照原始 PDF 逐项核对，修正错误

**产出**：`data/pdf_structured_manual.md`，完整的 4 大指标、20+ 子项的等级-分值-备注-认定部门数据。

**已接入知识库**：按 `---` 分隔符切分为 20 个独立段落，通过 `DocumentManager` 增量同步到 Qdrant，与 `eventlist.txt` 组成双数据源。

---

### 二、系统 Prompt 优化

**v1 — 原始状态**

12 条规则硬编码在 `HandbookService.java` 中，全部预设竞赛场景。问题：
- 无法处理论文/CSP/外语/任职等查询（占 PDF 数据的 70%+）
- 4 条规则（6/7/8/9）反复强调同一概念——"禁止把名次映射成奖项等级"，是反复打补丁的结果
- 规则 2 自相矛盾："结论先行，但是必须要推理完毕后再说出回答"
- 改一个字需重新编译部署

**v2 — 三层架构 + 关键词意图分类**

将 12 条规则重构为三层 Prompt，外置到 `application.yml`：

```
base（角色定义）→ competition/paper/general（关键词三选一注入）→ output（格式+铁则）
```

意图分类用三组关键词集合（paper 9 词 / competition 20 词 / general 25 词），优先级匹配。

**困难**：真实用户措辞不可控。"学术专长有什么加分项"——无任何关键词命中，误入 none；"我这个项目能加多少分"——同样漏判；"论文竞赛获奖"——同时命中两个意图，只能选一个。每发现一个漏判就加词，关键词列表持续膨胀，准确率约 60-80%。

**v3 — AI 单标签分类**

保留三层 Prompt 架构不动，将 `classifyIntent()` 中的关键词匹配替换为一次轻量 LLM 调用：

- 极简分类 prompt（~100 token），要求仅回复一个单词
- 延迟增加 ~200-500ms，但准确率提升到 90%+
- 失败时兜底返回 none，不影响主流程

**v4 — AI 多标签分类（当前方案）**

v3 只能输出单个意图，但真实场景下用户经常跨领域提问。改动两处：
- 分类 prompt 改为"可多选，用逗号分隔"
- `classifyIntent()` 返回值从 `String` 改为 `Set<String>`，遍历合并所有命中规则层

最终效果——"竞赛一等奖和EI论文总共能加多少分"同时注入 competition + paper 两组规则。

**关键设计决策**：Prompt 不存分值数据。原计划 YAML 示例中写了"第一等级一等奖 50 分"，实际实现只写行为约束（"分值严格等于上下文数据"），具体分值由向量检索上下文提供。避免数据重复，规则变更时只改向量库不改 Prompt。

---

### 三、知识库分块与检索调优（早期探索）

**比赛目录分块**：eventlist.txt 最初按空行分割（每等级一段），导致单段过大（50+ 个比赛），检索相似度低。迭代优化：
- 一段全部比赛 → 7~8 个一段 → 按类别分段（创新创业/设计/机器人/挑战/其它）（蓝桥杯相似度从 0.38 提升到 0.56）
