#!/usr/bin/env python3
"""Run the versioned, synthetic SQL-curation corpus through a local Ollama runtime."""
import argparse
import json
from pathlib import Path
import platform
import time
import urllib.error
import urllib.request

from curation_common import PROMPT_VERSION, parse_json_text, prompt_for, selected_cases, validate_response, write_manifest


def call(endpoint, path, body=None, timeout=300):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(endpoint.rstrip("/") + path, data=data, method="POST" if data else "GET")
    if data:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        message = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Ollama HTTP {error.code}: {message[-800:]}") from error
    except urllib.error.URLError as error:
        raise RuntimeError("Ollama local não está disponível; inicie o serviço e confirme o endpoint.") from error


def totals(runs):
    prompt = sum(run.get("usage", {}).get("prompt_eval_count", 0) or 0 for run in runs)
    output = sum(run.get("usage", {}).get("eval_count", 0) or 0 for run in runs)
    eval_nanos = sum(run.get("usage", {}).get("eval_duration", 0) or 0 for run in runs)
    return {
        "runs": len(runs), "durationMs": sum(run.get("durationMs", 0) for run in runs),
        "inputTokens": prompt, "outputTokens": output,
        "outputTokensPerSecond": round(output / (eval_nanos / 1_000_000_000), 3) if eval_nanos > 0 else None,
        "reportedCostUsd": None,
        "billing": "Inferência local; custo marginal de API não se aplica. Compute deve ser medido separadamente.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", required=True, help="nome local exibido por `ollama list`")
    parser.add_argument("--endpoint", default="http://127.0.0.1:11434")
    parser.add_argument("--hardware", default=None, help="descrição declarada do hardware")
    parser.add_argument("--queries", nargs="*")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory must be new")
    args.output.mkdir(parents=True)
    manifest = {
        "schemaVersion": 1, "promptVersion": PROMPT_VERSION, "provider": "ollama-local",
        "model": args.model, "endpoint": args.endpoint, "source": "synthetic-versioned-corpus-only",
        "hardware": args.hardware or platform.platform(), "runs": [],
        "security": {"targetDatabaseAccessed": False, "containsOnlySyntheticVersionedCorpus": True},
        "status": "planned" if args.dry_run else "running",
    }
    try:
        if not args.dry_run:
            manifest["runtime"] = call(args.endpoint, "/api/version")
        for case, context in selected_cases(args.queries):
            query_id = case["id"]
            prompt = prompt_for(context)
            prompt_file = args.output / f"{query_id}.prompt.json"
            prompt_file.write_text(json.dumps(context, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if args.dry_run:
                manifest["runs"].append({"queryId": query_id, "promptFile": prompt_file.name, "status": "planned"})
                continue
            started = time.monotonic()
            raw = call(args.endpoint, "/api/generate", {"model": args.model, "prompt": prompt, "stream": False, "format": "json"})
            duration_ms = round((time.monotonic() - started) * 1000)
            raw_file = args.output / f"{query_id}.ollama.json"
            raw_file.write_text(json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            run = {"queryId": query_id, "durationMs": duration_ms,
                   "usage": {key: raw.get(key) for key in ("prompt_eval_count", "eval_count", "prompt_eval_duration", "eval_duration", "total_duration")},
                   "rawUsageEvidenceFile": raw_file.name}
            try:
                response = parse_json_text(raw.get("response", ""))
                validate_response(response, query_id)
                response_file = args.output / f"{query_id}.response.json"
                response_file.write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                run.update({"status": "curated", "responseFile": response_file.name})
                print(f"{query_id}: curated, local usage preserved", flush=True)
            except (TypeError, ValueError, json.JSONDecodeError) as error:
                run.update({"status": "invalid_response", "validationError": str(error)})
                print(f"{query_id}: invalid response preserved ({error})", flush=True)
            manifest["runs"].append(run)
        if not args.dry_run:
            manifest["totals"] = totals(manifest["runs"])
            manifest["quality"] = {
                "validResponses": sum(item["status"] == "curated" for item in manifest["runs"]),
                "invalidResponses": sum(item["status"] == "invalid_response" for item in manifest["runs"]),
                "grounding": "Resposta validada por schema; avaliação B0–B3 requer execução separada do protocolo.",
            }
            manifest["status"] = "passed" if not manifest["quality"]["invalidResponses"] else "completed_with_invalid_responses"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        write_manifest(args.output, manifest)


if __name__ == "__main__":
    main()
