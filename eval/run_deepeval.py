"""
DeepEval RAG 评估服务
====================
启动：DEEPSEEK_API_KEY=sk-xxx python eval/run_deepeval.py
"""

import os

# ⚠️ 必须在 import deepeval 之前设置，否则不生效
os.environ["DEEPEVAL_PER_ATTEMPT_TIMEOUT_SECONDS_OVERRIDE"] = "180"
os.environ["DEEPEVAL_RETRY_MIN_WAIT_SECONDS"] = "0.8"
os.environ["DEEPEVAL_RETRY_MAX_WAIT_SECONDS"] = "3.0"

import json, logging
from datetime import datetime
from typing import List, Dict, Any

from dotenv import load_dotenv
load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("deepeval")

from deepeval import evaluate
from deepeval.evaluate.configs import AsyncConfig, CacheConfig
from deepeval.metrics import (
    FaithfulnessMetric, AnswerRelevancyMetric,
    ContextualRecallMetric, ContextualPrecisionMetric, HallucinationMetric,
)
from deepeval.test_case import LLMTestCase
from deepeval.models import DeepSeekModel

DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
if not DEEPSEEK_API_KEY:
    logger.warning("未设置 DEEPSEEK_API_KEY")


class DeepSeekV4Model(DeepSeekModel):
    """DeepSeek v4。绕过父类 trim_and_load_json，用自己的 _extract_json。"""

    def __init__(self, model: str = "deepseek-v4-pro", **kwargs):
        super().__init__(model=model, **kwargs)

    def _client_kwargs(self):
        kw = super()._client_kwargs()
        kw["timeout"] = 180.0
        return kw

    def generate(self, prompt: str, **kwargs):
        schema = kwargs.pop("schema", None)
        if isinstance(prompt, tuple): prompt = prompt[0]
        prompt = str(prompt)
        if "json" not in prompt.lower():
            prompt = prompt + "\nYou must output valid JSON."
        import json as _json

        raw, cost = super(DeepSeekV4Model, self).generate(prompt, **kwargs)
        json_str = self._extract_json(raw)
        if schema:
            return schema.model_validate(_json.loads(json_str)), cost
        return json_str, cost

    async def a_generate(self, prompt: str, **kwargs):
        schema = kwargs.pop("schema", None)
        if isinstance(prompt, tuple): prompt = prompt[0]
        prompt = str(prompt)
        if "json" not in prompt.lower():
            prompt = prompt + "\nYou must output valid JSON."
        import json as _json

        raw, cost = await super(DeepSeekV4Model, self).a_generate(prompt, **kwargs)
        json_str = self._extract_json(raw)
        if schema:
            return schema.model_validate(_json.loads(json_str)), cost
        return json_str, cost

    @staticmethod
    def _extract_json(text: str) -> str:
        for fence in ("```json", "```"):
            if fence in text:
                s = text.find(fence) + len(fence)
                e = text.find("```", s)
                if e != -1: text = text[s:e]
                break
        start = text.find("{")
        if start == -1: return text
        depth = 0
        for i in range(start, len(text)):
            if text[i] == "{": depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0: return text[start:i + 1]
        return text


from store import save_run as save_to_db

eval_model = DeepSeekV4Model()

app = FastAPI(title="DeepEval RAG Evaluation", version="1.0.0")


class TestItem(BaseModel):
    question: str
    answer: str
    contexts: List[str]
    ground_truth: str


class EvalRequest(BaseModel):
    test_items: List[TestItem]
    version: str = ""  # 版本标签，如 "v1.2" / "2025-07-25"


@app.get("/health")
def health():
    return {"status": "ok", "model": eval_model.get_model_name()}


@app.post("/evaluate-rag")
def evaluate_rag(request: EvalRequest) -> Dict[str, Any]:
    if not request.test_items:
        raise HTTPException(status_code=400, detail="test_items 不能为空")

    logger.info(f"开始评估 {len(request.test_items)} 条用例，Judge: {eval_model.get_model_name()}")

    metrics = [
        FaithfulnessMetric(model=eval_model, threshold=0.5, include_reason=True),
        AnswerRelevancyMetric(model=eval_model, threshold=0.5, include_reason=True),
        ContextualRecallMetric(model=eval_model, threshold=0.5, include_reason=True),
        ContextualPrecisionMetric(model=eval_model, threshold=0.5, include_reason=True),
        HallucinationMetric(model=eval_model, threshold=0.5, include_reason=True),
    ]

    test_cases = [
        LLMTestCase(
            input=it.question, actual_output=it.answer,
            retrieval_context=it.contexts, context=it.contexts,
            expected_output=it.ground_truth,
        )
        for it in request.test_items
    ]

    try:
        results = evaluate(
            test_cases=test_cases, metrics=metrics,
            async_config=AsyncConfig(run_async=True, max_concurrent=2),
            cache_config=CacheConfig(write_cache=True, use_cache=True),
        )
    except Exception as e:
        logger.error(f"评估失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))

    all_details = []
    all_scores = {k: [] for k in
        ["Faithfulness","Answer Relevancy","Contextual Recall","Contextual Precision","Hallucination"]}

    for i, tr in enumerate(results.test_results):
        scores = {}
        for md in (tr.metrics_data or []):
            if md.score is not None:
                scores[md.name] = md.score
                all_scores[md.name].append(md.score)
        all_details.append({
            "question": request.test_items[i].question,
            "faithfulness": scores.get("Faithfulness", 0.0),
            "answer_relevancy": scores.get("Answer Relevancy", 0.0),
            "context_recall": scores.get("Contextual Recall", 0.0),
            "context_precision": scores.get("Contextual Precision", 0.0),
            "hallucination": scores.get("Hallucination", 0.0),
        })

    def avg(lst):
        return round(sum(lst) / len(lst), 4) if lst else 0.0

    report = {
        "average_faithfulness": avg(all_scores["Faithfulness"]),
        "average_answer_relevancy": avg(all_scores["Answer Relevancy"]),
        "average_context_recall": avg(all_scores["Contextual Recall"]),
        "average_context_precision": avg(all_scores["Contextual Precision"]),
        "average_hallucination": avg(all_scores["Hallucination"]),
        "details": all_details,
        "model": eval_model.get_model_name(),
        "test_count": len(request.test_items),
    }

    # 持久化到 SQLite
    version = request.version or datetime.now().strftime("%Y-%m-%d_%H%M%S")
    try:
        run_id = save_to_db(version, report, all_details, model=eval_model.get_model_name())
        logger.info(f"✅ 已保存到 SQLite: run_id={run_id} version={version}")
    except Exception as e:
        logger.error(f"❌ SQLite 保存失败: {e}", exc_info=True)

    logger.info(f"评估完成({len(all_details)}条): f={report['average_faithfulness']} "
                f"ar={report['average_answer_relevancy']} cr={report['average_context_recall']}")
    return report


if __name__ == "__main__":
    port = int(os.environ.get("EVAL_PORT", "8000"))
    logger.info(f"启动: http://localhost:{port}  Judge: {eval_model.get_model_name()}")
    uvicorn.run(app, host="0.0.0.0", port=port)
