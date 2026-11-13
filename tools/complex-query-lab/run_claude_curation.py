#!/usr/bin/env python3
"""Run the isolated curation corpus through an already-authenticated Claude Code CLI."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from curation_common import PROMPT_VERSION, parse_json_text, prompt_for, selected_cases, validate_response, write_manifest

STDIN_INSTRUCTION = (
    "Leia integralmente o contexto UTF-8 recebido pela entrada padrão. Siga as instruções nele e responda somente "
    "com o objeto JSON solicitado, sem Markdown."
)


def claude_command(executable, model):
    command = [executable, "-p", STDIN_INSTRUCTION, "--output-format", "json", "--model", model,
               "--max-turns", "1", "--disallowedTools", "Bash,Read,Edit,Write,Glob,Grep,WebFetch,WebSearch"]
    # npm commonly installs Claude Code as claude.cmd on Windows. CreateProcess cannot execute that launcher
    # directly when shell=False. Prefer its sibling PowerShell shim, which safely preserves paths with spaces.
    if os.name == "nt" and Path(executable).suffix.lower() in {".cmd", ".bat"}:
        powershell_shim = Path(executable).with_suffix(".ps1")
        if powershell_shim.is_file():
            powershell = shutil.which("powershell.exe") or shutil.which("pwsh.exe") or "powershell.exe"
            return [powershell, "-NoProfile", "-File", str(powershell_shim), *command[1:]]
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", subprocess.list2cmdline(command)]
    if os.name == "nt" and Path(executable).suffix.lower() == ".ps1":
        powershell = shutil.which("powershell.exe") or shutil.which("pwsh.exe") or "powershell.exe"
        return [powershell, "-NoProfile", "-File", executable, *command[1:]]
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default="sonnet")
    parser.add_argument("--queries", nargs="*")
    parser.add_argument("--dry-run", action="store_true", help="write prompts and manifest without calling Claude")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory must be new")
    args.output.mkdir(parents=True)
    claude = shutil.which("claude")
    if not args.dry_run and not claude:
        parser.error("Claude Code CLI not found. Install and authenticate on this machine, then rerun; --dry-run needs no CLI.")
    if not args.dry_run:
        print(f"Claude launcher: {claude}", flush=True)
    manifest = {
        "schemaVersion": 1, "promptVersion": PROMPT_VERSION, "provider": "anthropic-claude-code-cli",
        "model": args.model, "source": "synthetic-versioned-corpus-only", "runs": [],
        "status": "planned" if args.dry_run else "running",
        "billing": "Claude Code subscription/API billing is not assumed to expose an invoice amount per run.",
    }
    try:
        for case, context in selected_cases(args.queries):
            query_id = case["id"]
            prompt = prompt_for(context)
            prompt_file = args.output / f"{query_id}.prompt.json"
            prompt_file.write_text(json.dumps(context, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if args.dry_run:
                manifest["runs"].append({"queryId": query_id, "promptFile": prompt_file.name, "status": "planned"})
                continue
            command = claude_command(claude, args.model)
            started = time.monotonic()
            # PowerShell shims can write stderr in a legacy Windows code page. Keep provider output as bytes,
            # preserve it verbatim, and decode only at the boundary where structured JSON is needed.
            result = subprocess.run(command, input=prompt.encode("utf-8"), capture_output=True, timeout=300)
            duration_ms = round((time.monotonic() - started) * 1000)
            raw_file = args.output / f"{query_id}.claude.json"
            raw_file.write_bytes(result.stdout)
            stdout = result.stdout.decode("utf-8", errors="replace")
            stderr = result.stderr.decode("utf-8", errors="replace")
            if result.returncode:
                raise RuntimeError(f"{query_id}: Claude Code failed with exit {result.returncode}: {stderr[-500:]}")
            envelope = json.loads(stdout)
            try:
                response = parse_json_text(envelope.get("result", ""))
                validate_response(response, query_id)
            except (TypeError, ValueError, json.JSONDecodeError) as error:
                manifest["runs"].append({
                    "queryId": query_id, "durationMs": duration_ms, "usage": envelope.get("usage") or {},
                    "reportedCostUsd": envelope.get("total_cost_usd"), "rawUsageEvidenceFile": raw_file.name,
                    "status": "invalid_structured_response", "providerResult": envelope.get("result", ""),
                })
                raise RuntimeError(f"{query_id}: Claude returned no valid curation JSON: {envelope.get('result', '')[:300]}") from error
            response_file = args.output / f"{query_id}.response.json"
            response_file.write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            usage = envelope.get("usage") or {}
            manifest["runs"].append({
                "queryId": query_id, "durationMs": duration_ms, "usage": usage,
                "reportedCostUsd": envelope.get("total_cost_usd"),
                "responseFile": response_file.name, "rawUsageEvidenceFile": raw_file.name,
            })
            print(f"{query_id}: curated, provider usage preserved", flush=True)
        manifest["status"] = "planned" if args.dry_run else "passed"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        write_manifest(args.output, manifest)


if __name__ == "__main__":
    main()
