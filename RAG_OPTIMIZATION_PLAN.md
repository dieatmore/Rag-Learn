# RAG 系统优化方案

> 聚焦四个核心方向，按优先级排列。

---

## 一、当前状态总览

| 方向 | 状态 | 说明 |
|------|------|------|
| 三层 Prompt + 多意图分类 | ✅ 已完成 | 底座层 + 规则层（多意图）+ 输出层，AI 分类支持多标签 |
| PDF 表格提取 | ⚠️ 初始完成 | 已产出 `pdf_structured_manual.md`，待深入打磨 |
| DeepEval 评估可视化 | 🔲 待开始 | Streamlit 页面，回归测试历史趋势图 |
| 流式输出 | 🔲 待开始 | SSE 逐字返回 |

---

## 二、三层 Prompt + 多意图分类 ✅

### 架构

```
用户问题
    │
    ▼
AI 多意图分类（chatClient 轻量调用，200-500ms）
    │
    ├─ competition  ─┐
    ├─ paper        ─┼─ 拼接多个规则层 ─┐
    ├─ general      ─┘                  │
    └─ none                             │
                                        ▼
                          base + 规则层们 + output
```

### 意图类别

| 意图 | 触发场景 |
|------|---------|
| `competition` | 竞赛、比赛、奖项、排名、名次、团队项目 |
| `paper` | 论文、期刊、发表、作者署名 |
| `general` | CSP认证、外语等级、荣誉称号、学生任职、参军实习、创新项目、操行评等、活动表彰 |
| `none` | 与以上均不相关 → 不注入规则层 |

### 当前实现

`HandbookService.java`：
- `classifyIntent(question)` → 返回 `Set<String>`（多标签）
- `getAnswer(question)` → 遍历意图集合，拼接多个规则层

```java
// 核心逻辑
Set<String> intents = classifyIntent(question);  // e.g. {"competition", "paper"}

StringBuilder rulesBuilder = new StringBuilder();
for (String intent : intents) {
    String rule = switch (intent) {
        case "competition" -> promptConfig.getCompetition();
        case "paper" -> promptConfig.getPaper();
        case "general" -> promptConfig.getGeneral();
        default -> null;
    };
    if (rule != null && !rule.isEmpty()) {
        if (!rulesBuilder.isEmpty()) rulesBuilder.append("\n");
        rulesBuilder.append(rule);
    }
}
```

### 涉及文件

| 文件 | 说明 |
|------|------|
| `config/PromptConfig.java` | `@ConfigurationProperties(prefix = "rag.prompts")` |
| `application.yml` | `rag.prompts` 节点（base / competition / paper / general / output） |
| `HandbookService.java` | 多意图分类 + 规则拼接 + RAG 调用 |

---

## 三、PDF 表格提取管道 ⚠️

### 当前状态

已通过 Python `pdfplumber` 粗提取 + AI 结构化 → 产出 `data/pdf_structured_manual.md`，覆盖 4 大一级指标、20+ 二级指标。

### 后续规划：独立仓库 + 工具化引入

PDF 表格解析需要深入打磨，不适合放在当前 RAG 问答仓库。计划：

```
┌─────────────────────────────────┐
│  新仓库: pdf-table-pipeline      │  ← 独立的 PDF 表格解析管道工具
│  ├── 多引擎适配（pdfplumber /    │
│  │    camelot / tabula）        │
│  ├── 合并单元格还原              │
│  ├── 跨页连续性处理              │
│  ├── AI 结构化输出               │
│  └── CLI / API 接口             │
└──────────────┬──────────────────┘
               │ 引入依赖 / submodule
               ▼
┌─────────────────────────────────┐
│  Rag-Learn（本项目）             │
│  ├── 调用管道工具解析 PDF        │
│  ├── 结构化 Markdown → 分块     │
│  └── Embedding → Qdrant         │
└─────────────────────────────────┘
```

### 管道核心能力

1. **多引擎适配**：pdfplumber（表格定位）、camelot（规则表格）、tabula（备选）
2. **合并单元格还原**：纵向/横向合并单元格的层级重建
3. **跨页连续性**：跨页表格自动拼接
4. **AI 结构化**：LLM 将机械提取的碎片重建为结构化 Markdown
5. **输出标准化**：统一的结构化 JSON/Markdown 输出格式

### 本项目集成方式

- Maven/Gradle 依赖或 Git submodule 引入
- 配置中指定 PDF 路径 + 输出目录
- 启动时自动调用管道解析 → 分块 → 入向量库

---

## 四、DeepEval 评估可视化 🔲

### 目标

用 Streamlit 搭建轻量可视化页面，展示 DeepEval 评估结果的历史趋势，支撑回归测试。

DeepEval 相比 Ragas 的优势：
- **原生支持通义千问**：通过 DashScope 自定义模型，无需额外适配
- **指标更全面**：忠实度、回答相关性、上下文召回率、上下文精准率、幻觉检测（5项 vs Ragas 的4项）
- **内置测试用例管理**：更完善的 CI/CD 集成能力

### 页面设计

```
┌──────────────────────────────────────────────────┐
│  DeepEval 评估看板                                 │
├──────────────────────────────────────────────────┤
│                                                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────┐ │
│  │ 忠实度    │ │ 相关性    │ │ 召回率    │ │ 精准率 │ │
│  │ 0.89 ↑   │ │ 0.92 →   │ │ 0.85 ↑   │ │ 0.88 ↑│ │
│  └──────────┘ └──────────┘ └──────────┘ └───────┘ │
│  ┌──────────────────────────────────────────────┐ │
│  │              幻觉检测: 0.95 ↑                  │ │
│  └──────────────────────────────────────────────┘ │
│                                                    │
│  ┌──────────────────────────────────────────────┐ │
│  │           历史趋势图（折线图）                  │ │
│  │  📈 faithfulness                              │ │
│  │  📈 answer_relevancy                          │ │
│  │  📈 context_recall                            │ │
│  │  📈 context_precision                         │ │
│  │  📈 hallucination                             │ │
│  │  x轴: 日期/版本    y轴: 分数                   │ │
│  └──────────────────────────────────────────────┘ │
│                                                    │
│  ┌──────────────────────────────────────────────┐ │
│  │           评估用例详情表                        │ │
│  │  问题 | 预期 | 实际 | 忠实度 | 相关性 | ...    │ │
│  └──────────────────────────────────────────────┘ │
│                                                    │
│  ┌──────────────────────────────────────────────┐ │
│  │           DeepEval 单次评估入口                 │ │
│  │   [选择测试集] [运行评估] → 结果入库 + 刷新图表 │ │
│  └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

### 数据流

```
DeepEval 评估脚本（Python，通义千问作为 Judge 模型）
    │
    ▼
评估结果 JSON（五维指标：忠实度/相关性/召回率/精准率/幻觉检测 + 逐条详情）
    │
    ▼
SQLite / 文件存储（按时间/版本记录）
    │
    ▼
Streamlit 页面 ← 读取历史数据，渲染趋势图
```

### 技术选型

- **DeepEval**：替代 Ragas，原生支持通义千问（DashScope 自定义模型），5 项评估指标
- **Streamlit**：纯 Python，零前端代码，适合内部工具
- **Plotly**：交互式折线图，Hover 显示详情
- **SQLite**：轻量存储，评估结果按时间戳归档

### 涉及文件（本项目内 `eval/` 目录）

| 文件 | 说明 |
|------|------|
| `eval/run_deepeval.py` | DeepEval 评估服务（FastAPI），使用通义千问 Judge，输出 JSON 结果 |
| `eval/requirements.txt` | Python 依赖（deepeval, fastapi, uvicorn, openai） |
| `eval/store.py` | 评估结果持久化（SQLite） |
| `eval/app.py` | Streamlit 可视化页面入口 |
| `eval/test_cases/` | 测试用例集 |

---

## 五、流式输出 🔲

### 当前状态

`chatClient.prompt()...call().content()` — 同步阻塞返回完整结果。

### 目标

SSE 流式输出，逐字返回，提升交互体验。

### 实现方案

```java
// HandbookService.java
public Flux<String> getAnswerStream(String question) {
    // ... 同样的检索 + Prompt 拼接逻辑 ...

    return chatClient.prompt()
            .advisors(qaAdvisor)
            .user(question)
            .stream()              // ← .stream() 替代 .call()
            .content();            // 返回 Flux<String>
}

// Controller
@GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> askStream(@RequestParam String question) {
    return handbookService.getAnswerStream(question)
            .map(chunk -> ServerSentEvent.<String>builder()
                    .data(chunk)
                    .build())
            .concatWithValues(
                ServerSentEvent.<String>builder()
                    .event("done").data("[DONE]").build()
            );
}
```

### 涉及文件

| 文件 | 变更 |
|------|------|
| `HandbookService.java` | 新增 `getAnswerStream()` 方法 |
| `RagController.java` | 新增 `/ask/stream` SSE 端点 |

---

## 六、实施顺序

```
已完成 ✅              短期 🔲                中期 🔲
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 三层 Prompt   │    │ 流式输出      │    │ DeepEval 可视化│
│ + 多意图分类  │───►│ (SSE)        │───►│ (Streamlit)  │
└──────────────┘    └──────────────┘    └──────────────┘
                                                │
                          ┌─────────────────────┘
                          ▼
                   ┌──────────────┐
                   │ PDF 管道工具   │
                   │ (独立仓库开发) │
                   └──────────────┘
```

**理由**：流式输出最快见效（几十行代码），DeepEval 可视化需要攒一些历史数据才有意义，PDF 管道独立开发不阻塞主仓库。

---

## 七、不纳入本期计划的内容

以下方向曾在早期版本的计划中，但当前阶段不优先投入：

| 方向 | 不做的理由 |
|------|-----------|
| 混合检索（Hybrid Search + BM25） | Qdrant Sparse Vector 配置复杂，当前场景 Dense 检索已基本够用 |
| Reranker 重排序 | DashScope Rerank API 可随时接入，但检索质量瓶颈不在排序而在知识覆盖 |
| 文档增量管理 | 规则文档更新频率极低（每学年一次），全量重建成本可接受 |
