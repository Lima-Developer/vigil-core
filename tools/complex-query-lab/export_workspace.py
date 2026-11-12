#!/usr/bin/env python3
"""Export only the repository's synthetic experiment evidence for the local UI."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT / 'tools/complex-query-lab'
EVIDENCE = ROOT / 'docs/experiments/complex-query-corpus'
catalog = json.loads((LAB / 'catalog.json').read_text())
run_directories = [EVIDENCE / f'scale-3-seed-{seed}' for seed in (42, 43)]
reports = [json.loads((directory / 'report.json').read_text()) for directory in run_directories]
if any(r['status'] != 'passed' for r in reports):
    raise ValueError('Only successful synthetic runs can be exported')
cases = []
for case in catalog['queries']:
    measurements = next(q for q in reports[0]['queries'] if q['id'] == case['id'])
    variants = {}
    for variant in ('original', 'candidate'):
        record = measurements['variants'][variant]
        variants[variant] = {**record, 'sql': (LAB / case[variant]).read_text(),
            'plan': json.loads((run_directories[0] / record['runs'][0]['plan_file']).read_text())}
    cases.append({**case, **measurements, 'variants': variants})
payload = {'schemaVersion': 1, 'source': 'synthetic-lab-measured',
    'observedAt': reports[0]['finished_at_utc'], 'modelUsage': None,
    'postgresVersion': reports[0]['postgres_version'], 'dataset': reports[0]['dataset'],
    'datasetSha256': reports[0]['dataset_sha256'], 'resourceLimits': reports[0]['resource_limits'],
    'seeds': [r['seed'] for r in reports], 'scale': reports[0]['scale'],
    'repetitions': reports[0]['repetitions'],
    'planCount': sum(len(variant['runs']) for report in reports for query in report['queries']
                     for variant in query['variants'].values()),
    'cases': cases}
output = ROOT / 'vigil-bootstrap/src/main/resources/static/corpus.json'
output.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')) + '\n')
print(f'Exported {len(cases)} measured synthetic scenarios to {output}')
