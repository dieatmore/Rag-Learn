"""
RAG 历史多版本回归看板
======================
Streamlit + Plotly，读取 SQLite 中的评估历史数据。

启动：streamlit run eval/app.py

未来迭代：
  - 自动对比基线，指标下降超阈值 → GitHub Actions 拦截合并
  - CLI 模式输出 JSON，供 CI 解析
"""

import streamlit as st
import plotly.graph_objects as go
import plotly.express as px
from plotly.subplots import make_subplots
import pandas as pd
from datetime import datetime

from store import (
    list_runs, get_run, get_run_details,
    set_baseline, get_baseline, get_baseline_details,
    compare_to_baseline, get_threshold_alerts,
    METRICS, init_db,
)

# ── 页面配置 ──────────────────────────────────────────
st.set_page_config(
    page_title="RAG 评估回归看板",
    page_icon="📊",
    layout="wide",
)
st.title("📊 RAG 评估回归看板")
st.caption("DeepEval 五维指标 · 多版本历史趋势 · 基线对比")

init_db()

# ── 侧边栏 ──────────────────────────────────────────
with st.sidebar:
    st.header("⚙️ 控制面板")

    # 版本列表
    runs = list_runs()
    if not runs:
        st.warning("暂无评估数据。请先运行一次评估。")
        st.stop()

    versions = [f"#{r['id']} {r['version']} ({r['run_at'][:10]})" for r in runs]
    selected_idx = st.selectbox("📌 选择版本", range(len(versions)),
                                format_func=lambda i: versions[i])

    selected_run = runs[selected_idx]

    st.divider()

    # 基线管理
    baseline = get_baseline()
    if baseline:
        st.info(f"📏 当前基线: **#{baseline['id']} {baseline['version']}**")
    else:
        st.warning("📏 未设置基线")

    col1, col2 = st.columns(2)
    with col1:
        if st.button("📌 设当前版本为基线", use_container_width=True):
            set_baseline(selected_run["id"], selected_run["version"])
            st.rerun()
    with col2:
        if baseline and st.button("❌ 清除基线", use_container_width=True):
            import sqlite3, os
            db = sqlite3.connect(os.path.join(os.path.dirname(__file__), "eval_history.db"))
            db.execute("DELETE FROM baselines")
            db.commit()
            db.close()
            st.rerun()

    st.divider()

    # 阈值配置（供未来 CI 使用）
    st.subheader("🔧 回归阈值")
    threshold_faithfulness = st.slider("Faithfulness 最大降幅", 0.01, 0.30, 0.05, 0.01,
                                       help="当前版本比基线低超过此值视为退化")
    threshold_relevancy = st.slider("Answer Relevancy 最大降幅", 0.01, 0.30, 0.05, 0.01)
    threshold_recall = st.slider("Context Recall 最大降幅", 0.01, 0.30, 0.10, 0.01)
    threshold_precision = st.slider("Context Precision 最大降幅", 0.01, 0.30, 0.10, 0.01)
    threshold_hallucination = st.slider("Hallucination 最大升幅", 0.01, 0.30, 0.10, 0.01,
                                        help="幻觉指标越低越好，升高超过阈值视为退化")

    st.divider()
    st.caption("💡 提示：先跑 Java 测试生成数据，再刷新此页面。")

# ── 主区域 ──────────────────────────────────────────

# ── KPI 卡片行 ──
st.subheader(f"📈 版本概览: #{selected_run['id']} {selected_run['version']}")

kpi_cols = st.columns(5)
metrics_display = [
    ("Faithfulness", "avg_faithfulness", "忠实度", "🔵"),
    ("Answer Relevancy", "avg_answer_relevancy", "回答相关性", "🟢"),
    ("Context Recall", "avg_context_recall", "上下文召回", "🟣"),
    ("Context Precision", "avg_context_precision", "上下文精准", "🟠"),
    ("Hallucination", "avg_hallucination", "幻觉检测", "🔴"),
]

diffs = compare_to_baseline(selected_run["id"]) if baseline else None

for i, (name, key, label, icon) in enumerate(metrics_display):
    val = selected_run.get(key, 0) or 0
    with kpi_cols[i]:
        delta_str = ""
        if diffs and name.lower() in diffs:
            d = diffs[name.lower()]["delta"]
            arrow = "↑" if d > 0 else "↓" if d < 0 else "→"
            # hallucination is inverted (lower is better)
            color = "inverse" if name == "Hallucination" else "normal"
            delta_color = "inverse" if name == "Hallucination" else "normal"
            # For hallucination, increase is bad
            if name == "Hallucination":
                delta_str = f"{d:+.4f} {arrow}"
            else:
                delta_str = f"{d:+.4f} {arrow}"
        st.metric(
            label=f"{icon} {label}",
            value=f"{val:.4f}",
            delta=delta_str if delta_str else None,
        )

# ── 基线对比告警 ──
if diffs and baseline:
    thresholds = {
        "faithfulness": threshold_faithfulness,
        "answer_relevancy": threshold_relevancy,
        "context_recall": threshold_recall,
        "context_precision": threshold_precision,
        "hallucination": threshold_hallucination,
    }
    alerts = get_threshold_alerts(diffs, thresholds)
    if alerts:
        st.warning("⚠️ 基线回归告警（当前版本 vs 基线 #" + str(baseline["id"]) + "）：\n\n" + "\n\n".join(alerts))
    else:
        st.success(f"✅ 所有指标均在阈值范围内（vs 基线 #{baseline['id']}）")

st.divider()

# ── 历史趋势图 ──
st.subheader("📉 历史趋势")

# 准备趋势数据（按执行时间排序，x 轴显示版本标签）
runs_df = pd.DataFrame(runs)
runs_df = runs_df.sort_values("run_at")

fig = make_subplots(specs=[[{"secondary_y": False}]])
colors = {"faithfulness": "#3366cc", "answer_relevancy": "#109618",
          "context_recall": "#990099", "context_precision": "#ff9900",
          "hallucination": "#dc3912"}

for name, key, label, _ in metrics_display:
    fig.add_trace(go.Scatter(
        x=runs_df["version"], y=runs_df[key],
        mode="lines+markers", name=label,
        line=dict(color=colors.get(name.lower().replace(" ", "_"), "#999"), width=2),
        marker=dict(size=8),
        hovertemplate=f"<b>{label}</b>: %{{y:.4f}}<br>版本: %{{x}}<extra></extra>",
    ))

# 标注基线
if baseline:
    fig.add_shape(type="line",
                  x0=baseline["version"], x1=baseline["version"], y0=0, y1=1,
                  line=dict(dash="dash", color="green", width=2),
                  xref="x", yref="paper")
    fig.add_annotation(x=baseline["version"], y=1.02, text="基线", showarrow=False,
                       xref="x", yref="paper", font=dict(color="green"))

# 标注当前选中版本
fig.add_shape(type="line",
              x0=selected_run["version"], x1=selected_run["version"], y0=0, y1=1,
              line=dict(dash="dot", color="red", width=2),
              xref="x", yref="paper")
fig.add_annotation(x=selected_run["version"], y=1.02, text="当前", showarrow=False,
                   xref="x", yref="paper", font=dict(color="red"))

fig.update_layout(
    height=450, margin=dict(l=10, r=10, t=10, b=10),
    legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="right", x=1),
    hovermode="x unified",
    yaxis=dict(title="分数", range=[0, 1.05]),
)
st.plotly_chart(fig, use_container_width=True)

# ── 版本对比表 ──
st.subheader("📋 版本对比")

compare_df = runs_df[["id", "version", "run_at", "test_count",
    "avg_faithfulness", "avg_answer_relevancy", "avg_context_recall",
    "avg_context_precision", "avg_hallucination"]].copy()
compare_df = compare_df.sort_values("run_at", ascending=False)
compare_df["run_at"] = compare_df["run_at"].str[:16]

# 格式化数字
for c in compare_df.columns:
    if c.startswith("avg_"):
        compare_df[c] = compare_df[c].apply(lambda x: f"{x:.4f}" if pd.notna(x) else "-")

compare_df.columns = ["ID", "版本", "时间", "用例数",
    "忠实度", "相关性", "召回率", "精准率", "幻觉"]

st.dataframe(compare_df, use_container_width=True, hide_index=True)

st.divider()

# ── 逐条详情 ──
st.subheader(f"🔍 逐条详情 — #{selected_run['id']} {selected_run['version']}")

details = get_run_details(selected_run["id"])

# 基线详情对比
bl_details = {}
if baseline and baseline["id"] != selected_run["id"]:
    bl_details = {d["question"]: d for d in get_baseline_details()}
    show_baseline_col = True
else:
    show_baseline_col = False

if details:
    rows = []
    for d in details:
        row = {
            "问题": d["question"][:60] + ("..." if len(d["question"]) > 60 else ""),
            "忠实度": f"{d['faithfulness']:.4f}",
            "相关性": f"{d['answer_relevancy']:.4f}",
            "召回率": f"{d['context_recall']:.4f}",
            "精准率": f"{d['context_precision']:.4f}",
            "幻觉": f"{d['hallucination']:.4f}",
        }
        if show_baseline_col and d["question"] in bl_details:
            bd = bl_details[d["question"]]
            for m in ["faithfulness", "answer_relevancy", "context_recall", "context_precision", "hallucination"]:
                delta = (d[m] or 0) - (bd[m] or 0)
                label_map = {"faithfulness": "忠实度", "answer_relevancy": "相关性",
                             "context_recall": "召回率", "context_precision": "精准率",
                             "hallucination": "幻觉"}
                arrow = "↑" if delta > 0 else "↓" if delta < 0 else "→"
                row[f"{label_map[m]} vs基线"] = f"{delta:+.4f} {arrow}"
        rows.append(row)
    st.dataframe(pd.DataFrame(rows), use_container_width=True, hide_index=True)
else:
    st.info("暂无详情数据")

# ── 页脚 ──
st.divider()
st.caption(
    "🔮 后续迭代计划：自动对比基线 → 指标下降超阈值 → GitHub Actions 拦截 PR 合并。"
    "CLI 模式：`python eval/store.py --check-baseline --thresholds faithfulness=0.05,...` 供 CI 调用。"
)
