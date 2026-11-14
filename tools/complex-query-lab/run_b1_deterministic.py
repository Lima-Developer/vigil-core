#!/usr/bin/env python3
"""Execute B1: regras determinísticas sobre as evidências dos dois seeds."""
import argparse
import json
from pathlib import Path

from curation_common import LAB, validate_response, write_manifest


SEEDS = (42, 43)
IMPROVEMENT_THRESHOLD = 0.10


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def measurements(query_id):
    values = []
    for seed in SEEDS:
        report = read_json(LAB.parents[1] / "docs/experiments/complex-query-corpus" /
                           f"scale-3-seed-{seed}" / "report.json")
        query = next(item for item in report["queries"] if item["id"] == query_id)
        values.append({
            "seed": seed,
            "equivalentMultiset": query["equivalent_multiset"],
            "originalMedianMs": query["variants"]["original"]["median_execution_ms"],
            "candidateMedianMs": query["variants"]["candidate"]["median_execution_ms"],
        })
    return values


def decide(case, values):
    if not all(value["equivalentMultiset"] for value in values):
        return "human_review", ["equivalência multiconjunto falhou em pelo menos um seed"]
    if case["control"]:
        return "original", ["controle negativo: as variantes versionadas são idênticas"]
    ratios = [value["candidateMedianMs"] / value["originalMedianMs"] for value in values]
    if all(ratio <= 1 - IMPROVEMENT_THRESHOLD for ratio in ratios):
        return "candidate", [f"candidata foi ao menos {IMPROVEMENT_THRESHOLD:.0%} mais rápida em ambos os seeds"]
    if all(ratio >= 1 + IMPROVEMENT_THRESHOLD for ratio in ratios):
        return "original", [f"original foi ao menos {IMPROVEMENT_THRESHOLD:.0%} mais rápida em ambos os seeds"]
    return "human_review", ["ganho não foi consistente ou não superou o limiar nos dois seeds"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory must be new")
    catalog = read_json(LAB / "catalog.json")
    args.output.mkdir(parents=True)
    manifest = {
        "schemaVersion": 1,
        "protocol": "B1",
        "provider": "deterministic-rules",
        "source": "synthetic-versioned-corpus-only",
        "rule": {"bothSeeds": list(SEEDS), "minimumRelativeMedianImprovement": IMPROVEMENT_THRESHOLD},
        "runs": [],
        "status": "running",
    }
    try:
        for case in catalog["queries"]:
            values = measurements(case["id"])
            decision, rationale = decide(case, values)
            response = {
                "queryId": case["id"], "decision": decision, "rationale": rationale,
                "planEvidence": [
                    f"seed {value['seed']}: original={value['originalMedianMs']}ms; candidate={value['candidateMedianMs']}ms"
                    for value in values
                ],
                "semanticRisks": [
                    "regra não prova equivalência universal; usa apenas a equivalência multiconjunto dos dois seeds"
                ],
                "confidence": 1.0,
            }
            validate_response(response, case["id"])
            filename = f"{case['id']}.response.json"
            (args.output / filename).write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            manifest["runs"].append({"queryId": case["id"], "responseFile": filename, "measurements": values})
            print(f"{case['id']}: {decision}")
        manifest["status"] = "passed"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        write_manifest(args.output, manifest)


if __name__ == "__main__":
    main()
