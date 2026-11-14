#!/usr/bin/env python3
"""Compare B1, B2 and B3 decisions with the versioned human B0 baseline."""
import argparse
import json
from pathlib import Path


EXPECTED = {f"Q{number:02d}" for number in range(1, 11)}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def b0_decisions(path):
    values = {item["queryId"]: item["decision"] for item in read_json(path)["evaluations"]}
    if set(values) != EXPECTED:
        raise ValueError("B0 must contain Q01 through Q10")
    return values


def responses(directory, expected_protocol):
    manifest = read_json(directory / "manifest.json")
    if manifest.get("status") != "passed" or manifest.get("protocol") != expected_protocol:
        raise ValueError(f"{directory} is not a passed {expected_protocol} directory")
    runs = {run["queryId"]: run for run in manifest["runs"]}
    if set(runs) != EXPECTED:
        raise ValueError(f"{expected_protocol} must contain Q01 through Q10")
    return manifest, {query_id: read_json(directory / run["responseFile"]) for query_id, run in runs.items()}


def score(baseline, evaluated):
    rows = [{"queryId": query_id, "b0": baseline[query_id], "decision": evaluated[query_id]["decision"],
             "exact": baseline[query_id] == evaluated[query_id]["decision"]} for query_id in sorted(EXPECTED)]
    exact = sum(row["exact"] for row in rows)
    unsafe = [row["queryId"] for row in rows if row["decision"] == "candidate" and row["b0"] != "candidate"]
    missed = [row["queryId"] for row in rows if row["b0"] == "candidate" and row["decision"] != "candidate"]
    reviewed = [row["queryId"] for row in rows if row["b0"] == "human_review" and row["decision"] == "human_review"]
    return {"queries": rows, "exactDecisionAgreement": exact / len(rows), "unsafeCandidatePromotions": unsafe,
            "missedCandidatePromotions": missed, "humanReviewAgreement": reviewed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--b0", type=Path, required=True)
    parser.add_argument("--b1-directory", type=Path, required=True)
    parser.add_argument("--b2-directory", type=Path, required=True)
    parser.add_argument("--b3-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output file must be new")
    baseline = b0_decisions(args.b0)
    result = {"schemaVersion": 1, "baseline": str(args.b0), "protocols": {}}
    for name, directory in (("B1", args.b1_directory), ("B2", args.b2_directory), ("B3", args.b3_directory)):
        manifest, evaluated = responses(directory, name)
        result["protocols"][name] = {"provider": manifest["provider"], "model": manifest.get("model"),
                                      "score": score(baseline, evaluated), "totals": manifest.get("totals")}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
