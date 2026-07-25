"""
评估结果 SQLite 持久化 + 基线管理。

表：
  eval_runs     — 每次评估的 5 项汇总指标
  eval_details  — 每条用例的逐项分数
  baselines     — 标记为基线的版本（用于回归对比）
"""

import sqlite3, os
from datetime import datetime
from typing import Dict, List, Optional

DB_PATH = os.path.join(os.path.dirname(__file__), "eval_history.db")


def _conn():
    c = sqlite3.connect(DB_PATH)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL")
    return c


def init_db():
    with _conn() as db:
        db.executescript("""
            CREATE TABLE IF NOT EXISTS eval_runs (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                version     TEXT    NOT NULL,
                run_at      TEXT    NOT NULL,
                model       TEXT,
                test_count  INTEGER,
                avg_faithfulness      REAL,
                avg_answer_relevancy  REAL,
                avg_context_recall    REAL,
                avg_context_precision REAL,
                avg_hallucination     REAL
            );

            CREATE TABLE IF NOT EXISTS eval_details (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id      INTEGER NOT NULL REFERENCES eval_runs(id),
                question    TEXT,
                faithfulness       REAL,
                answer_relevancy   REAL,
                context_recall     REAL,
                context_precision  REAL,
                hallucination      REAL
            );

            CREATE TABLE IF NOT EXISTS baselines (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id      INTEGER NOT NULL UNIQUE REFERENCES eval_runs(id),
                label       TEXT,
                created_at  TEXT    NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_runs_version ON eval_runs(version);
            CREATE INDEX IF NOT EXISTS idx_runs_run_at  ON eval_runs(run_at);
            CREATE INDEX IF NOT EXISTS idx_details_run   ON eval_details(run_id);
        """)
        db.commit()


# ── 写入 ──────────────────────────────────────────────

def save_run(version: str, report: Dict, details: List[Dict], model: str = "") -> int:
    """保存一次完整评估，返回 run_id。"""
    init_db()
    with _conn() as db:
        cur = db.execute(
            """INSERT INTO eval_runs (version, run_at, model, test_count,
               avg_faithfulness, avg_answer_relevancy, avg_context_recall,
               avg_context_precision, avg_hallucination)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (version, datetime.now().isoformat(), model, report.get("test_count"),
             report["average_faithfulness"], report["average_answer_relevancy"],
             report["average_context_recall"], report["average_context_precision"],
             report["average_hallucination"]),
        )
        run_id = cur.lastrowid
        for d in details:
            db.execute(
                """INSERT INTO eval_details (run_id, question, faithfulness,
                   answer_relevancy, context_recall, context_precision, hallucination)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (run_id, d["question"], d["faithfulness"], d["answer_relevancy"],
                 d["context_recall"], d["context_precision"], d["hallucination"]),
            )
        db.commit()
        return run_id


# ── 查询 ──────────────────────────────────────────────

def list_runs(limit: int = 50) -> List[Dict]:
    """历史版本列表，最新在前。"""
    init_db()
    with _conn() as db:
        rows = db.execute(
            "SELECT * FROM eval_runs ORDER BY run_at DESC LIMIT ?", (limit,)
        ).fetchall()
        return [dict(r) for r in rows]


def get_run(run_id: int) -> Optional[Dict]:
    with _conn() as db:
        row = db.execute("SELECT * FROM eval_runs WHERE id = ?", (run_id,)).fetchone()
        return dict(row) if row else None


def get_run_details(run_id: int) -> List[Dict]:
    with _conn() as db:
        rows = db.execute(
            "SELECT * FROM eval_details WHERE run_id = ?", (run_id,)
        ).fetchall()
        return [dict(r) for r in rows]


def get_latest_run() -> Optional[Dict]:
    runs = list_runs(1)
    return runs[0] if runs else None


# ── 基线管理 ───────────────────────────────────────────

def set_baseline(run_id: int, label: str = "") -> int:
    """将某次 run 设为基线（先清掉旧基线）。"""
    init_db()
    with _conn() as db:
        db.execute("DELETE FROM baselines")
        cur = db.execute(
            "INSERT INTO baselines (run_id, label, created_at) VALUES (?, ?, ?)",
            (run_id, label or f"baseline-{run_id}", datetime.now().isoformat()),
        )
        db.commit()
        return cur.lastrowid


def get_baseline() -> Optional[Dict]:
    """获取当前基线 run 的汇总数据。"""
    init_db()
    with _conn() as db:
        row = db.execute(
            """SELECT r.*, b.label as baseline_label
               FROM baselines b JOIN eval_runs r ON b.run_id = r.id
               ORDER BY b.created_at DESC LIMIT 1"""
        ).fetchone()
        return dict(row) if row else None


def get_baseline_details() -> List[Dict]:
    """获取基线条目的逐条详情。"""
    baseline = get_baseline()
    if not baseline:
        return []
    return get_run_details(baseline["id"])


# ── 对比工具 ───────────────────────────────────────────

METRICS = ["faithfulness", "answer_relevancy", "context_recall",
           "context_precision", "hallucination"]


def compare_to_baseline(run_id: int) -> Optional[Dict]:
    """对比某次 run 与基线的差值。"""
    baseline = get_baseline()
    run = get_run(run_id)
    if not baseline or not run:
        return None
    diffs = {}
    for m in METRICS:
        b_val = baseline.get(f"avg_{m}", 0) or 0
        r_val = run.get(f"avg_{m}", 0) or 0
        diffs[m] = {"baseline": b_val, "current": r_val, "delta": round(r_val - b_val, 4)}
    return diffs


def get_threshold_alerts(diffs: Dict, thresholds: Dict[str, float]) -> List[str]:
    """检查哪些指标下降超过了阈值。thresholds 如 {"faithfulness": 0.05, ...}"""
    alerts = []
    for m, d in diffs.items():
        th = thresholds.get(m, 0.1)
        if d["delta"] < -th:
            alerts.append(f"⚠ {m}: {d['delta']:+.4f} (阈值 -{th})")
    return alerts
