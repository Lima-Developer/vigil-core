#!/usr/bin/env python3
"""Run isolated, sanitised SQL curation via OpenRouter without storing the API key."""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

from curation_common import PROMPT_VERSION, parse_json_text, prompt_for, selected_cases, validate_response, write_manifest


API_URL = "https://openrouter.ai/api/v1/chat/completions"
SYSTEM_PROMPT = "Você retorna apenas JSON válido e não executa nem gera SQL."


def price_upper_bound_usd(run_count, input_cap, output_cap, pricing):
    return round(run_count * (input_cap * pricing["input"] + output_cap * pricing["output"]) / 1_000_000, 6)


def call_openrouter(api_key, body):
    request = urllib.request.Request(API_URL, data=json.dumps(body).encode("utf-8"), method="POST")
    request.add_header("Authorization", f"Bearer {api_key}")
    request.add_header("Content-Type", "application/json")
    request.add_header("X-OpenRouter-Metadata", "enabled")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        message = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OpenRouter HTTP {error.code}: {message[-800:]}") from error


def totals(runs):
    usage_keys = ("prompt_tokens", "completion_tokens", "total_tokens", "reasoning_tokens")
    usage = {key: sum(run.get("usage", {}).get(key, 0) for run in runs
                      if isinstance(run.get("usage", {}).get(key), (int, float))) for key in usage_keys}
    reported_costs = [run["reportedCostUsd"] for run in runs if isinstance(run.get("reportedCostUsd"), (int, float))]
    return {
        "runs": len(runs), "durationMs": sum(run.get("durationMs", 0) for run in runs),
        "usage": usage,
        "reportedCostUsd": round(sum(reported_costs), 8) if len(reported_costs) == len(runs) else None,
        "reportedCostStatus": "all_runs_reported" if len(reported_costs) == len(runs) else "not_available_for_every_run",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default="qwen/qwen3.8-27b")
    parser.add_argument("--pricing", type=Path, default=Path(__file__).with_name("pricing-2026-11-13-openrouter.json"))
    parser.add_argument("--queries", nargs="*")
    parser.add_argument("--max-input-tokens", type=int, default=18000)
    parser.add_argument("--max-output-tokens", type=int, default=700)
    parser.add_argument("--max-cost-usd", type=float, default=0.05)
    parser.add_argument("--dry-run", action="store_true", help="write sanitized prompts and cost bound without making HTTP requests")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory must be new")
    if min(args.max_input_tokens, args.max_output_tokens, args.max_cost_usd) <= 0:
        parser.error("token and cost limits must be positive")
    pricing_data = json.loads(args.pricing.read_text(encoding="utf-8"))
    prices = pricing_data["models"].get(args.model)
    if not prices:
        parser.error(f"no pinned price for {args.model} in {args.pricing}")
    cases = list(selected_cases(args.queries))
    upper_bound = price_upper_bound_usd(len(cases), args.max_input_tokens, args.max_output_tokens, prices)
    if upper_bound > args.max_cost_usd:
        parser.error(f"worst-case ${upper_bound:.6f} exceeds --max-cost-usd ${args.max_cost_usd:.6f}; increase intentionally")
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not args.dry_run and not api_key:
        parser.error("OPENROUTER_API_KEY is required (it is read only from the environment and never written).")
    args.output.mkdir(parents=True)
    manifest = {
        "schemaVersion": 1, "promptVersion": PROMPT_VERSION, "provider": "openrouter",
        "model": args.model, "source": "synthetic-versioned-corpus-only", "runs": [],
        "status": "planned" if args.dry_run else "running", "pricing": {"source": prices["source"], "inputPer1M": prices["input"], "outputPer1M": prices["output"]},
        "guardrails": {"maxInputTokensPerRun": args.max_input_tokens, "maxOutputTokensPerRun": args.max_output_tokens,
                       "maxCostUsd": args.max_cost_usd, "worstCaseCostUsd": upper_bound},
    }
    try:
        for case, context in cases:
            query_id = case["id"]
            prompt = prompt_for(context)
            prompt_file = args.output / f"{query_id}.prompt.json"
            prompt_file.write_text(json.dumps(context, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if args.dry_run:
                manifest["runs"].append({"queryId": query_id, "promptFile": prompt_file.name, "status": "planned"})
                continue
            body = {"model": args.model, "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": prompt}],
                    "temperature": 0, "max_tokens": args.max_output_tokens,
                    "response_format": {"type": "json_object"}}
            started = time.monotonic()
            raw = call_openrouter(api_key, body)
            duration_ms = round((time.monotonic() - started) * 1000)
            raw_file = args.output / f"{query_id}.openrouter.json"
            raw_file.write_text(json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            content = raw.get("choices", [{}])[0].get("message", {}).get("content", "")
            response = parse_json_text(content)
            validate_response(response, query_id)
            response_file = args.output / f"{query_id}.response.json"
            response_file.write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            usage = raw.get("usage") or {}
            manifest["runs"].append({
                "queryId": query_id, "durationMs": duration_ms, "usage": usage,
                "reportedCostUsd": usage.get("cost") if isinstance(usage.get("cost"), (int, float)) else None,
                "responseFile": response_file.name, "rawUsageEvidenceFile": raw_file.name,
            })
            print(f"{query_id}: curated, provider usage preserved", flush=True)
        if not args.dry_run:
            manifest["totals"] = totals(manifest["runs"])
        manifest["status"] = "planned" if args.dry_run else "passed"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        write_manifest(args.output, manifest)


if __name__ == "__main__":
    main()
