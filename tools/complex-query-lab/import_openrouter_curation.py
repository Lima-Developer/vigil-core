#!/usr/bin/env python3
"""Import a completed OpenRouter curation run into versioned evidence and dashboard data."""
import argparse
import json
from pathlib import Path
import shutil

from curation_common import validate_response


ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {f"Q{number:02d}" for number in range(1, 11)}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sum_usage(runs, key):
    return sum(run.get("usage", {}).get(key, 0) for run in runs
               if isinstance(run.get("usage", {}).get(key), (int, float)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-directory", type=Path, required=True)
    parser.add_argument("--destination-directory", type=Path,
                        default=ROOT / "docs/experiments/llm-curation/qwen3.8-27b-openrouter")
    parser.add_argument("--dashboard-summary", type=Path,
                        default=ROOT / "vigil-bootstrap/src/main/resources/static/curation.json")
    args = parser.parse_args()
    args.source_directory = args.source_directory.resolve()
    args.destination_directory = args.destination_directory.resolve()
    args.dashboard_summary = args.dashboard_summary.resolve()
    if args.destination_directory.exists():
        parser.error("destination directory must be new")

    manifest_path = args.source_directory / "manifest.json"
    manifest = read_json(manifest_path)
    if manifest.get("status") != "passed" or manifest.get("provider") != "openrouter":
        parser.error("source manifest must be a passed OpenRouter run")
    runs = manifest.get("runs", [])
    if {run.get("queryId") for run in runs} != EXPECTED or len(runs) != 10:
        parser.error("source manifest must contain exactly Q01 through Q10")
    if any(run.get("status") != "curated" for run in runs):
        parser.error("source manifest contains an invalid response; retain it as quality evidence but do not import it as a benchmark")

    model = manifest.get("model")
    if not isinstance(model, str) or not model:
        parser.error("source manifest has no model")
    by_query = {run["queryId"]: run for run in runs}
    responses = {}
    for query_id in sorted(EXPECTED):
        run = by_query[query_id]
        response_path = args.source_directory / run.get("responseFile", "")
        raw_path = args.source_directory / run.get("rawUsageEvidenceFile", "")
        if not response_path.is_file() or not raw_path.is_file():
            parser.error(f"{query_id} is missing response or raw evidence")
        response = read_json(response_path)
        try:
            validate_response(response, query_id)
        except ValueError as error:
            parser.error(str(error))
        raw = read_json(raw_path)
        if raw.get("model") != model:
            parser.error(f"{query_id} raw evidence model mismatch")
        responses[query_id] = response

    args.destination_directory.mkdir(parents=True)
    shutil.copyfile(manifest_path, args.destination_directory / "manifest.json")
    for query_id, run in by_query.items():
        shutil.copyfile(args.source_directory / run["responseFile"], args.destination_directory / run["responseFile"])
        shutil.copyfile(args.source_directory / run["rawUsageEvidenceFile"], args.destination_directory / run["rawUsageEvidenceFile"])

    decisions = [{
        **responses[query_id], "durationMs": by_query[query_id]["durationMs"],
        "usage": by_query[query_id]["usage"], "reportedCostUsd": by_query[query_id].get("reportedCostUsd"),
    } for query_id in sorted(EXPECTED)]
    totals = manifest.get("totals") or {}
    execution = {
        "provider": "openrouter", "model": model, "runs": len(runs),
        "durationMs": totals.get("durationMs", sum(run["durationMs"] for run in runs)),
        "tokens": {
            "input_tokens": sum_usage(runs, "prompt_tokens"),
            "output_tokens": sum_usage(runs, "completion_tokens"),
            "total_tokens": sum_usage(runs, "total_tokens"),
            "reasoning_tokens": sum_usage(runs, "reasoning_tokens"),
        },
        "billedCostUsd": totals.get("reportedCostUsd"),
        "billingReason": "Custo efetivo reportado pelo OpenRouter por execução; inclui a rota do provedor utilizada.",
        "quality": manifest.get("quality"), "decisions": decisions,
        "artifactDirectory": str(args.destination_directory.relative_to(ROOT)),
    }
    if not isinstance(execution["billedCostUsd"], (int, float)):
        parser.error("OpenRouter did not report a total billed cost for every run")

    summary = read_json(args.dashboard_summary)
    previous = summary.get("additionalExecutions", [])
    if any(item.get("provider") == execution["provider"] and item.get("model") == model for item in previous):
        parser.error(f"dashboard summary already contains an execution for {model}")
    summary.setdefault("providerAvailability", {})["openrouter"] = {
        "status": "executed", "model": model, "runs": len(runs),
        "billedCostUsd": round(execution["billedCostUsd"], 6), "quality": execution["quality"],
    }
    summary["additionalExecutions"] = [*previous, execution]
    args.dashboard_summary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Imported {len(runs)} OpenRouter runs ({model}) into {args.destination_directory}")


if __name__ == "__main__":
    main()
