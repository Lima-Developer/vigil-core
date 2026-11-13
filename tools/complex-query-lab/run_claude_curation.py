#!/usr/bin/env python3
"""Run the isolated curation corpus through an already-authenticated Claude Code CLI."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time

from curation_common import PROMPT_VERSION, parse_json_text, prompt_for, selected_cases, validate_response, write_manifest


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
    if not args.dry_run and not shutil.which("claude"):
        parser.error("Claude Code CLI not found. Install and authenticate on this machine, then rerun; --dry-run needs no CLI.")
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
            prompt_file.write_text(json.dumps(context, ensure_ascii=False, indent=2) + "\n")
            if args.dry_run:
                manifest["runs"].append({"queryId": query_id, "promptFile": prompt_file.name, "status": "planned"})
                continue
            command = ["claude", "-p", prompt, "--output-format", "json", "--model", args.model,
                       "--max-turns", "1", "--disallowedTools", "Bash,Read,Edit,Write,Glob,Grep,WebFetch,WebSearch"]
            started = time.monotonic()
            result = subprocess.run(command, text=True, capture_output=True, timeout=300)
            duration_ms = round((time.monotonic() - started) * 1000)
            raw_file = args.output / f"{query_id}.claude.json"
            raw_file.write_text(result.stdout)
            if result.returncode:
                raise RuntimeError(f"{query_id}: Claude Code failed with exit {result.returncode}: {result.stderr[-500:]}")
            envelope = json.loads(result.stdout)
            response = parse_json_text(envelope.get("result", ""))
            validate_response(response, query_id)
            response_file = args.output / f"{query_id}.response.json"
            response_file.write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n")
            usage = envelope.get("usage") or {}
            manifest["runs"].append({
                "queryId": query_id, "durationMs": duration_ms, "usage": usage,
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
