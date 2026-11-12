#!/usr/bin/env python3
"""Create a reproducible summary without turning projections into billed cost."""
import argparse
import json
from pathlib import Path


def projection(tokens, prices):
    total_input = tokens['input_tokens']
    cached = tokens['cached_input_tokens']
    uncached = total_input - cached
    if uncached < 0:
        raise ValueError('cached input cannot exceed total input')
    usd = (uncached * prices['input'] + cached * prices['cachedInput']
           + tokens['output_tokens'] * prices['output']) / 1_000_000
    return {
        'usd': round(usd, 6),
        'uncachedInputTokens': uncached,
        'cachedInputTokens': cached,
        'outputTokens': tokens['output_tokens'],
        'method': 'same-token projection; output already includes the reported reasoning subset',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-directory', type=Path, required=True)
    parser.add_argument('--pricing', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.run_directory / 'manifest.json').read_text())
    pricing = json.loads(args.pricing.read_text())
    if manifest['status'] != 'passed' or len(manifest['runs']) != 10:
        raise ValueError('summary requires ten successful runs')
    tokens = manifest['totals']
    decisions = []
    for run in manifest['runs']:
        response = json.loads((args.run_directory / run['responseFile']).read_text())
        decisions.append({
            'queryId': run['queryId'], 'decision': response['decision'],
            'confidence': response['confidence'], 'durationMs': run['durationMs'], 'usage': run['usage'],
            'rationale': response['rationale'], 'planEvidence': response['planEvidence'],
            'semanticRisks': response['semanticRisks'],
        })
    summary = {
        'schemaVersion': 1,
        'generatedFrom': str(args.run_directory),
        'actualExecution': {
            'provider': manifest['provider'], 'model': manifest['model'],
            'reasoningEffort': manifest['reasoningEffort'], 'runs': len(manifest['runs']),
            'tokens': tokens, 'durationMs': sum(run['durationMs'] for run in manifest['runs']),
            'billedCostUsd': None,
            'billingReason': 'The local CLI used the user subscription; no invoice amount was reported per run.',
        },
        'pricing': {
            'effectiveDate': pricing['effectiveDate'], 'currency': pricing['currency'],
            'projections': {model: {**projection(tokens, prices), 'source': prices['source'], 'note': prices['note']}
                            for model, prices in pricing['models'].items()},
        },
        'providerAvailability': {
            'claude-code': {'status': 'not_executed', 'reason': 'CLI_NOT_INSTALLED_ON_2026-11-12'}
        },
        'decisions': decisions,
    }
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
