#!/usr/bin/env python3
"""Import a completed Claude Code curation run into versioned experiment evidence and dashboard data."""
import argparse
import json
from pathlib import Path
import shutil


ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {f"Q{number:02d}" for number in range(1, 11)}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sum_usage(runs, key):
    return sum(run["usage"].get(key, 0) for run in runs if isinstance(run.get("usage", {}).get(key), (int, float)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-directory", type=Path, required=True)
    parser.add_argument("--destination-directory", type=Path,
                        default=ROOT / "docs/experiments/llm-curation/claude-sonnet-4-6")
    parser.add_argument("--dashboard-summary", type=Path,
                        default=ROOT / "vigil-bootstrap/src/main/resources/static/curation.json")
    args = parser.parse_args()
    if args.destination_directory.exists():
        parser.error("destination directory must be new")
    manifest_path = args.source_directory / "manifest.json"
    manifest = read_json(manifest_path)
    if manifest.get("status") != "passed" or manifest.get("provider") != "anthropic-claude-code-cli":
        parser.error("source manifest must be a passed Claude Code run")
    runs = manifest.get("runs", [])
    if {run.get("queryId") for run in runs} != EXPECTED or len(runs) != 10:
        parser.error("source manifest must contain exactly Q01 through Q10")
    by_query = {run["queryId"]: run for run in runs}
    responses = {}
    model_names = set()
    for query_id in sorted(EXPECTED):
        run = by_query[query_id]
        response_path = args.source_directory / run["responseFile"]
        raw_path = args.source_directory / run["rawUsageEvidenceFile"]
        if not response_path.is_file() or not raw_path.is_file():
            parser.error(f"{query_id} is missing response or raw evidence")
        response = read_json(response_path)
        if response.get("queryId") != query_id:
            parser.error(f"{query_id} response identity mismatch")
        responses[query_id] = response
        raw = read_json(raw_path)
        raw_events = raw if isinstance(raw, list) else [raw]
        for event in raw_events:
            model_names.update((event.get("modelUsage") or {}).keys())
    if len(model_names) != 1:
        parser.error(f"expected one actual Claude model, got: {sorted(model_names)}")
    model = next(iter(model_names))
    args.destination_directory.mkdir(parents=True)
    shutil.copyfile(manifest_path, args.destination_directory / "manifest.json")
    for query_id, run in by_query.items():
        shutil.copyfile(args.source_directory / run["responseFile"], args.destination_directory / run["responseFile"])
        shutil.copyfile(args.source_directory / run["rawUsageEvidenceFile"], args.destination_directory / run["rawUsageEvidenceFile"])

    decisions = []
    for query_id in sorted(EXPECTED):
        run, response = by_query[query_id], responses[query_id]
        decisions.append({
            **response, "durationMs": run["durationMs"], "usage": run["usage"],
            "reportedCostUsd": run.get("reportedCostUsd"),
        })
    execution = {
        "provider": manifest["provider"], "model": model, "runs": len(runs),
        "durationMs": sum(run["durationMs"] for run in runs),
        "tokens": {
            "input_tokens": sum_usage(runs, "input_tokens"),
            "cache_creation_input_tokens": sum_usage(runs, "cache_creation_input_tokens"),
            "cache_read_input_tokens": sum_usage(runs, "cache_read_input_tokens"),
            "output_tokens": sum_usage(runs, "output_tokens"),
        },
        "billedCostUsd": round(sum(run.get("reportedCostUsd", 0) for run in runs), 6),
        "billingReason": "Valor reportado pelo Claude Code por execução; inclui cache creation/read e não é projeção.",
        "decisions": decisions,
        "artifactDirectory": str(args.destination_directory.relative_to(ROOT)),
    }
    summary = read_json(args.dashboard_summary)
    summary["providerAvailability"]["claude-code"] = {
        "status": "executed", "model": model, "runs": len(runs), "billedCostUsd": execution["billedCostUsd"],
    }
    summary["additionalExecutions"] = [execution]
    args.dashboard_summary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Imported {len(runs)} Claude runs ({model}) into {args.destination_directory}")


if __name__ == "__main__":
    main()
