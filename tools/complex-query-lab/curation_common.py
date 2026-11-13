"""Shared, dependency-free helpers for the isolated SQL-curation runners."""
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LAB = Path(__file__).resolve().parent
EVIDENCE = ROOT / "docs/experiments/complex-query-corpus/scale-3-seed-42"
PROMPT_VERSION = "vigil-curation-v1"


def compact_plan(node):
    result = {
        "node": node["Node Type"],
        "estimatedCost": node.get("Total Cost"),
        "estimatedRows": node.get("Plan Rows"),
        "actualRows": node.get("Actual Rows"),
        "loops": node.get("Actual Loops"),
    }
    if node.get("Plans"):
        result["children"] = [compact_plan(child) for child in node["Plans"]]
    return result


def selected_cases(query_ids):
    catalog = json.loads((LAB / "catalog.json").read_text())
    report = json.loads((EVIDENCE / "report.json").read_text())
    selected = set(query_ids or [query["id"] for query in catalog["queries"]])
    known = {query["id"] for query in catalog["queries"]}
    if not selected or not selected <= known:
        raise ValueError("queries must be IDs from Q01 to Q10")
    for case in catalog["queries"]:
        if case["id"] not in selected:
            continue
        measurement = next(query for query in report["queries"] if query["id"] == case["id"])
        variants = {}
        for name in ("original", "candidate"):
            record = measurement["variants"][name]
            plan = json.loads((EVIDENCE / record["runs"][0]["plan_file"]).read_text())
            variants[name] = {
                "sql": (LAB / case[name]).read_text().strip(),
                "medianExecutionMs": record["median_execution_ms"],
                "p95ExecutionMs": record["p95_execution_ms"],
                "minExecutionMs": record["min_execution_ms"],
                "maxExecutionMs": record["max_execution_ms"],
                "plan": compact_plan(plan[0]["Plan"]),
            }
        yield case, {
            "promptVersion": PROMPT_VERSION,
            "queryId": case["id"],
            "title": case["title"],
            "expectedSemantics": case["expected"],
            "priorHypothesis": case["hypothesis"],
            "knownRisk": case["risk"],
            "control": case["control"],
            "experiment": {
                "postgres": "16.4", "scale": 3, "seed": 42, "repetitions": 7,
                "equivalentMultisetOnSeeds": [42, 43],
            },
            "variants": variants,
        }


def prompt_for(context):
    return (
        "Você é o curador SQL do Vigil. Avalie SOMENTE as variantes versionadas no JSON abaixo. "
        "Não crie SQL, não use ferramentas, não consulte arquivos e não recomende execução no target. "
        "Escolha candidate, original ou human_review. Trate tempos como evidência exploratória, custo do "
        "planner como unidade sem preço e equivalência em dois seeds como evidência limitada. Responda "
        "somente com um JSON válido: queryId, decision, rationale (1-3 textos), planEvidence (1-4 textos), "
        "semanticRisks (1-4 textos), confidence (0 a 1).\n\n" +
        json.dumps(context, ensure_ascii=False, separators=(",", ":"))
    )


def validate_response(response, query_id):
    if not isinstance(response, dict) or response.get("queryId") != query_id:
        raise ValueError(f"{query_id}: response identity mismatch")
    if response.get("decision") not in {"candidate", "original", "human_review"}:
        raise ValueError(f"{query_id}: invalid decision")
    confidence = response.get("confidence")
    if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
        raise ValueError(f"{query_id}: invalid confidence")
    for field, minimum, maximum in (("rationale", 1, 3), ("planEvidence", 1, 4), ("semanticRisks", 1, 4)):
        value = response.get(field)
        if not isinstance(value, list) or not minimum <= len(value) <= maximum or not all(isinstance(item, str) and item for item in value):
            raise ValueError(f"{query_id}: invalid {field}")


def parse_json_text(text):
    """Accept a JSON object even if a hosted model adds a Markdown fence."""
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1].rsplit("```", 1)[0].strip()
    return json.loads(text)


def write_manifest(output, manifest):
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
