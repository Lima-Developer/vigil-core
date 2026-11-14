#!/usr/bin/env python3
"""Run B2 or B3 with an authenticated Claude Code CLI and versioned synthetic evidence only."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from curation_common import parse_json_text, selected_cases, validate_response, write_manifest
from run_claude_curation import claude_command


STDIN_INSTRUCTION = "Leia integralmente o contexto UTF-8 recebido pela entrada padrão e responda apenas com o JSON solicitado."
GROUNDING_KEYS = {"semanticContract", "measurements", "plans", "b1"}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_b1(directory):
    manifest = read_json(directory / "manifest.json")
    if manifest.get("status") != "passed" or manifest.get("protocol") != "B1":
        raise ValueError("--b1-directory must contain a passed B1 manifest")
    runs = {run["queryId"]: run for run in manifest.get("runs", [])}
    if set(runs) != {f"Q{number:02d}" for number in range(1, 11)}:
        raise ValueError("B1 must contain exactly Q01 through Q10")
    return {query_id: read_json(directory / run["responseFile"]) for query_id, run in runs.items()}


def public_context(context):
    return {
        "queryId": context["queryId"], "title": context["title"],
        "semanticContract": context["expectedSemantics"], "experiment": context["experiment"],
        "variants": context["variants"],
    }


def prompt(protocol, context, b1_response=None):
    payload = public_context(context)
    if protocol == "B3":
        payload["deterministicEvidence"] = {
            "decision": b1_response["decision"],
            "rationale": b1_response["rationale"],
            "planEvidence": b1_response["planEvidence"],
        }
        instruction = (
            "B3 do Vigil: faça curadoria híbrida. Use apenas o contrato semântico, as medições, planos e "
            "evidência determinística fornecidos. Não gere SQL, não use ferramentas e não recomende executar no target. "
            "Você pode manter ou encaminhar a decisão B1 para revisão humana, mas não a ignore sem evidência explícita. "
            "Responda somente JSON válido com queryId, decision (candidate|original|human_review), rationale (1-3 textos), "
            "planEvidence (1-4 textos), semanticRisks (1-4 textos), confidence (0 a 1), grounding (1-4 itens dos valores: "
            "semanticContract, measurements, plans, b1).\n\n"
        )
    else:
        instruction = (
            "B2 do Vigil: faça curadoria apenas a partir do contrato semântico, SQL versionado, medições e planos fornecidos. "
            "Não gere SQL, não use ferramentas, não consulte arquivos e não receba regras determinísticas externas. "
            "Responda somente JSON válido com queryId, decision (candidate|original|human_review), rationale (1-3 textos), "
            "planEvidence (1-4 textos), semanticRisks (1-4 textos), confidence (0 a 1).\n\n"
        )
    return instruction + json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def validate_grounding(response):
    grounding = response.get("grounding")
    if not isinstance(grounding, list) or not 1 <= len(grounding) <= 4:
        raise ValueError("B3 response needs 1-4 grounding items")
    if not all(isinstance(item, str) and item in GROUNDING_KEYS for item in grounding):
        raise ValueError("B3 grounding contains an unsupported item")


def totals(runs):
    keys = ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")
    return {key: sum(run.get("usage", {}).get(key, 0) for run in runs if isinstance(run.get("usage", {}).get(key), (int, float))) for key in keys}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", choices=("B2", "B3"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default="sonnet")
    parser.add_argument("--b1-directory", type=Path)
    parser.add_argument("--queries", nargs="*")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory must be new")
    if args.protocol == "B3" and not args.b1_directory:
        parser.error("B3 requires --b1-directory")
    b1 = load_b1(args.b1_directory) if args.protocol == "B3" else None
    claude = shutil.which("claude")
    if not args.dry_run and not claude:
        parser.error("Claude Code CLI not found; authenticate it first. --dry-run needs no CLI.")
    args.output.mkdir(parents=True)
    manifest = {"schemaVersion": 1, "protocol": args.protocol, "provider": "anthropic-claude-code-cli",
                "model": args.model, "source": "synthetic-versioned-corpus-only", "runs": [],
                "status": "planned" if args.dry_run else "running"}
    try:
        for case, context in selected_cases(args.queries):
            query_id = case["id"]
            text = prompt(args.protocol, context, b1.get(query_id) if b1 else None)
            prompt_file = args.output / f"{query_id}.prompt.json"
            prompt_file.write_text(json.dumps({"protocol": args.protocol, "prompt": text}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if args.dry_run:
                manifest["runs"].append({"queryId": query_id, "promptFile": prompt_file.name, "status": "planned"})
                continue
            started = time.monotonic()
            result = subprocess.run(claude_command(claude, args.model), input=(STDIN_INSTRUCTION + "\n\n" + text).encode("utf-8"), capture_output=True, timeout=300)
            duration_ms = round((time.monotonic() - started) * 1000)
            raw_file = args.output / f"{query_id}.claude.json"
            raw_file.write_bytes(result.stdout)
            stdout, stderr = result.stdout.decode("utf-8", errors="replace"), result.stderr.decode("utf-8", errors="replace")
            if result.returncode:
                raise RuntimeError(f"{query_id}: Claude failed with exit {result.returncode}: {stderr[-500:]}")
            envelope = json.loads(stdout)
            response = parse_json_text(envelope.get("result", ""))
            validate_response(response, query_id)
            if args.protocol == "B3":
                validate_grounding(response)
            response_file = args.output / f"{query_id}.response.json"
            response_file.write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            manifest["runs"].append({"queryId": query_id, "responseFile": response_file.name,
                                     "rawUsageEvidenceFile": raw_file.name, "durationMs": duration_ms,
                                     "usage": envelope.get("usage") or {}, "reportedCostUsd": envelope.get("total_cost_usd")})
            print(f"{query_id}: {args.protocol} curated")
        if not args.dry_run:
            manifest["totals"] = {"tokens": totals(manifest["runs"]), "durationMs": sum(run["durationMs"] for run in manifest["runs"])}
        manifest["status"] = "planned" if args.dry_run else "passed"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        write_manifest(args.output, manifest)


if __name__ == "__main__":
    main()
