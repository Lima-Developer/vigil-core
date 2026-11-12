#!/usr/bin/env python3
"""Ask Codex to curate only the two versioned SQL alternatives and preserve provider-reported usage."""
import argparse
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
LAB = Path(__file__).resolve().parent
EVIDENCE = ROOT / 'docs/experiments/complex-query-corpus/scale-3-seed-42'
SCHEMA = LAB / 'curation-output.schema.json'
PROMPT_VERSION = 'vigil-curation-v1'


def compact_plan(node):
    result = {
        'node': node['Node Type'],
        'estimatedCost': node.get('Total Cost'),
        'estimatedRows': node.get('Plan Rows'),
        'actualRows': node.get('Actual Rows'),
        'loops': node.get('Actual Loops'),
    }
    if node.get('Plans'):
        result['children'] = [compact_plan(child) for child in node['Plans']]
    return result


def event_usage(events):
    completed = [event for event in events if event.get('type') == 'turn.completed']
    if len(completed) != 1 or not isinstance(completed[0].get('usage'), dict):
        raise ValueError('Codex did not report one final usage event')
    usage = completed[0]['usage']
    required = ('input_tokens', 'cached_input_tokens', 'output_tokens')
    if any(not isinstance(usage.get(key), int) for key in required):
        raise ValueError('Codex usage is incomplete')
    return usage


def final_message(events):
    messages = [event['item']['text'] for event in events
                if event.get('type') == 'item.completed'
                and event.get('item', {}).get('type') == 'agent_message'
                and isinstance(event.get('item', {}).get('text'), str)]
    if not messages:
        raise ValueError('Codex did not return a final message')
    return json.loads(messages[-1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--model', default='gpt-6-sol')
    parser.add_argument('--reasoning-effort', default='low', choices=('low', 'medium', 'high'))
    parser.add_argument('--queries', nargs='*')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    catalog = json.loads((LAB / 'catalog.json').read_text())
    report = json.loads((EVIDENCE / 'report.json').read_text())
    selected = set(args.queries or [query['id'] for query in catalog['queries']])
    known = {query['id'] for query in catalog['queries']}
    if not selected or not selected <= known:
        parser.error('queries must be IDs from Q01 to Q10')
    manifest = {
        'schemaVersion': 1,
        'promptVersion': PROMPT_VERSION,
        'provider': 'openai-codex-cli',
        'model': args.model,
        'reasoningEffort': args.reasoning_effort,
        'billing': 'actual invoice unavailable; token-equivalent projection is calculated separately',
        'source': 'synthetic-versioned-corpus-only',
        'runs': [],
        'status': 'running',
    }
    try:
        for case in catalog['queries']:
            if case['id'] not in selected:
                continue
            measurement = next(query for query in report['queries'] if query['id'] == case['id'])
            variants = {}
            for name in ('original', 'candidate'):
                record = measurement['variants'][name]
                plan = json.loads((EVIDENCE / record['runs'][0]['plan_file']).read_text())
                variants[name] = {
                    'sql': (LAB / case[name]).read_text().strip(),
                    'medianExecutionMs': record['median_execution_ms'],
                    'p95ExecutionMs': record['p95_execution_ms'],
                    'minExecutionMs': record['min_execution_ms'],
                    'maxExecutionMs': record['max_execution_ms'],
                    'plan': compact_plan(plan[0]['Plan']),
                }
            context = {
                'promptVersion': PROMPT_VERSION,
                'queryId': case['id'],
                'title': case['title'],
                'expectedSemantics': case['expected'],
                'priorHypothesis': case['hypothesis'],
                'knownRisk': case['risk'],
                'control': case['control'],
                'experiment': {'postgres': '16.4', 'scale': 3, 'seed': 42, 'repetitions': 7,
                               'equivalentMultisetOnSeeds': [42, 43]},
                'variants': variants,
            }
            prompt = (
                'Você é o curador SQL do Vigil. Avalie SOMENTE as variantes versionadas no JSON abaixo. '
                'Não crie SQL, não use ferramentas, não consulte arquivos e não recomende execução no target. '
                'Escolha candidate, original ou human_review. Trate tempos como evidência exploratória, custo do '
                'planner como unidade sem preço e equivalência em dois seeds como evidência limitada. Responda '
                'estritamente segundo o schema fornecido.\n\n' +
                json.dumps(context, ensure_ascii=False, separators=(',', ':'))
            )
            command = [
                'codex', 'exec', '--json', '--ephemeral', '--ignore-user-config', '--ignore-rules',
                '--model', args.model, '-c', f'model_reasoning_effort="{args.reasoning_effort}"',
                '--sandbox', 'read-only', '--cd', str(ROOT), '--output-schema', str(SCHEMA), '-'
            ]
            started = time.monotonic()
            result = subprocess.run(command, input=prompt, text=True, capture_output=True, timeout=300)
            duration_ms = round((time.monotonic() - started) * 1000)
            raw_path = args.output / f"{case['id']}.events.jsonl"
            raw_path.write_text(result.stdout)
            if result.returncode:
                raise RuntimeError(f"{case['id']}: Codex failed with exit {result.returncode}: {result.stderr[-500:]}")
            events = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
            response = final_message(events)
            if response.get('queryId') != case['id']:
                raise ValueError(f"{case['id']}: response identity mismatch")
            response_path = args.output / f"{case['id']}.response.json"
            response_path.write_text(json.dumps(response, ensure_ascii=False, indent=2) + '\n')
            usage = event_usage(events)
            manifest['runs'].append({
                'queryId': case['id'], 'durationMs': duration_ms, 'usage': usage,
                'responseFile': response_path.name, 'rawUsageEvidenceFile': raw_path.name,
            })
            print(f"{case['id']}: curated, usage preserved", flush=True)
        totals = {key: sum(run['usage'].get(key, 0) for run in manifest['runs'])
                  for key in ('input_tokens', 'cached_input_tokens', 'cache_write_input_tokens',
                              'output_tokens', 'reasoning_output_tokens')}
        manifest['totals'] = totals
        manifest['status'] = 'passed'
    except Exception as error:
        manifest['status'] = 'failed'
        manifest['error'] = str(error)
        raise
    finally:
        (args.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
