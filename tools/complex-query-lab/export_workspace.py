#!/usr/bin/env python3
"""Export only the repository's synthetic experiment evidence for the local UI."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT / 'tools/complex-query-lab'
EVIDENCE = ROOT / 'docs/experiments/complex-query-corpus'
catalog = json.loads((LAB / 'catalog.json').read_text())
reports = [json.loads((EVIDENCE / f'seed-{seed}/report.json').read_text()) for seed in (42, 43)]
if any(r['status'] != 'passed' for r in reports):
    raise ValueError('Only successful synthetic runs can be exported')
cases = []
for case in catalog['queries']:
    measurements = next(q for q in reports[0]['queries'] if q['id'] == case['id'])
    variants = {}
    for variant in ('original', 'candidate'):
        record = measurements['variants'][variant]
        variants[variant] = {**record, 'sql': (LAB / case[variant]).read_text(),
            'plan': json.loads((EVIDENCE / 'seed-42' / record['runs'][0]['plan_file']).read_text())}
    cases.append({**case, **measurements, 'variants': variants})
payload = {'schemaVersion': 1, 'source': 'synthetic-lab-measured',
    'observedAt': reports[0]['finished_at_utc'], 'modelUsage': None,
    'postgresVersion': reports[0]['postgres_version'], 'dataset': reports[0]['dataset'],
    'datasetSha256': reports[0]['dataset_sha256'], 'resourceLimits': reports[0]['resource_limits'],
    'seeds': [r['seed'] for r in reports], 'cases': cases}
output = ROOT / 'vigil-bootstrap/src/main/resources/static/corpus.json'
output.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')) + '\n')
print(f'Exported {len(cases)} measured synthetic scenarios to {output}')
