#!/usr/bin/env python3
"""Execute only the versioned synthetic corpus in a newly created isolated container."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parent
IMAGE = 'postgres@sha256:e62fbf9d3e2b49816a32c400ed2dba83e3b361e6833e624024309c35d334b412'


def command(args, stdin=None, timeout=120):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed: {result.stderr.strip()} {result.stdout.strip()}')
    return result.stdout.strip()


def positive(value):
    result = int(value)
    if result < 1:
        raise argparse.ArgumentTypeError('must be positive')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scale', type=positive, default=1)
    parser.add_argument('--seed', type=positive, default=42)
    parser.add_argument('--repetitions', type=positive, default=3)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.scale > 10 or args.seed > 1000000 or args.repetitions > 30:
        parser.error('limits: scale <= 10, seed <= 1000000, repetitions <= 30')
    # Exclusive directory avoids silently replacing previous evidence.
    args.output.mkdir(parents=True, exist_ok=False)
    name = 'vigil-corpus-' + uuid.uuid4().hex[:12]
    report = {'corpus_version': 1, 'seed': args.seed, 'scale': args.scale,
              'image': IMAGE, 'repetitions': args.repetitions, 'queries': [],
              'started_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'measurement': 'isolated synthetic lab; warm cache; alternating order; not production evidence',
              'resource_limits': {'cpus': 2, 'memory_mib': 1024, 'data_tmpfs_mib': 512, 'network': 'none'},
              'llm_usage': None, 'status': 'running'}

    def psql(sql, readonly=True):
        prefix = "SET timezone='UTC'; SET search_path=corpus,public; SET statement_timeout='20s'; SET lock_timeout='2s'; "
        if readonly:
            sql = 'BEGIN READ ONLY; ' + prefix + sql + '; COMMIT;'
        return command(['docker', 'exec', '-i', name, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1',
                        '-v', f'scale={args.scale}', '-v', f'seed={args.seed}', '-U', 'postgres', '-d', 'corpus'], sql)

    def sql_file(relative):
        path = (ROOT / relative).resolve()
        if not path.is_relative_to(ROOT / 'queries'):
            raise ValueError('SQL must be a versioned corpus query')
        return path.read_text().strip().removesuffix(';')

    try:
        command(['docker', 'run', '--detach', '--rm', '--name', name, '--network', 'none',
                 '--memory', '1g', '--cpus', '2', '--tmpfs', '/var/lib/postgresql/data:rw,size=512m',
                 '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=corpus', IMAGE,
                 '-c', 'max_connections=10', '-c', 'shared_buffers=128MB', '-c', 'work_mem=4MB'])
        for attempt in range(60):
            probe = subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'postgres', '-d', 'corpus'],
                                   capture_output=True, timeout=10)
            if probe.returncode == 0:
                # The image's temporary initialization server has no TCP listener.
                tcp = subprocess.run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1',
                                      '-U', 'postgres', '-d', 'corpus'], capture_output=True, timeout=10)
                if tcp.returncode == 0:
                    break
            time.sleep(1)
        else:
            raise RuntimeError('PostgreSQL did not become ready')
        seed = (ROOT / 'seed.sql').read_text()
        psql(seed, readonly=False)
        report['seed_sql_sha256'] = hashlib.sha256(seed.encode()).hexdigest()
        report['postgres_version'] = psql('SELECT version()')
        report['settings'] = json.loads(psql("SELECT json_object_agg(name,setting) FROM pg_settings WHERE name IN ('work_mem','shared_buffers','max_parallel_workers_per_gather','jit','TimeZone')"))
        tables = ['customers', 'categories', 'products', 'orders', 'order_items', 'payments', 'events']
        report['dataset'] = {}
        for table in tables:
            # Hash all ordered rows, including NULLs, not only table cardinality.
            value = json.loads(psql(f"SELECT json_build_object('rows',count(*),'md5',md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id))) FROM corpus.{table} t"))
            report['dataset'][table] = value
        expected = {'customers': 2000*args.scale, 'categories': 41, 'products': 502,
                    'orders': 20000*args.scale, 'order_items': 60000*args.scale+2,
                    'payments': 20000*args.scale-(20000*args.scale)//3+2000*args.scale,
                    'events': 12000*args.scale+4}
        assert {k: v['rows'] for k, v in report['dataset'].items()} == expected, 'seed cardinality mismatch'
        report['dataset_sha256'] = hashlib.sha256(json.dumps(report['dataset'], sort_keys=True).encode()).hexdigest()
        catalog = json.loads((ROOT / 'catalog.json').read_text())
        report['catalog_sha256'] = hashlib.sha256((ROOT / 'catalog.json').read_bytes()).hexdigest()
        report['runner_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        for case in catalog['queries']:
            queries = {v: sql_file(case[v]) for v in ('original', 'candidate')}
            original, candidate = queries['original'], queries['candidate']
            difference = int(psql(f'WITH a AS ({original}), b AS ({candidate}) SELECT count(*) FROM ((SELECT * FROM a EXCEPT ALL SELECT * FROM b) UNION ALL (SELECT * FROM b EXCEPT ALL SELECT * FROM a)) differences'))
            if difference:
                raise AssertionError(f"{case['id']}: {difference} non-equivalent rows")
            row_count = int(psql(f'SELECT count(*) FROM ({original}) result'))
            if row_count == 0:
                raise AssertionError(f"{case['id']}: empty scenario, invalid fixture")
            entry = {'id': case['id'], 'control': case['control'], 'equivalent_multiset': True,
                     'result_rows': row_count, 'variants': {}}
            # Independent edge expectations prevent two equally wrong rewrites from passing unnoticed.
            checks = {
                'Q02': 'SELECT count(*)>0 FROM result WHERE revenue IS NULL AND latest IS NULL',
                'Q05': 'SELECT count(*)=2 FROM result WHERE product_id IN (501,502) AND position=1',
                'Q06': 'SELECT count(*)=2 FROM result WHERE (id=1 AND products=502) OR (id=41 AND products=0)',
                'Q07': f'SELECT NOT EXISTS (SELECT 1 FROM result WHERE customer_id>{1800*args.scale}) AND NOT EXISTS (SELECT 1 FROM result GROUP BY customer_id HAVING count(*)>3)',
                'Q08': f'SELECT count(*)=1 FROM result WHERE id={2000*args.scale} AND completed=0 AND pending=0 AND revenue IS NULL',
                'Q10': f'SELECT count(*)=1 FROM result WHERE customer_id=1 AND event_id={12000*args.scale+4}',
            }
            if case['id'] in checks:
                for variant, sql in queries.items():
                    assert psql(f"WITH result AS ({sql}) {checks[case['id']]}") == 't', f"{case['id']} {variant}: edge expectation failed"
                entry['edge_expectations_passed'] = True
            for variant, sql in queries.items():
                entry['variants'][variant] = {'sql_sha256': hashlib.sha256(sql.encode()).hexdigest(), 'runs': []}
                # One warmup per variant, excluded from timings.
                psql(f'EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) {sql}')
            for repeat in range(args.repetitions):
                order = ('original', 'candidate') if repeat % 2 == 0 else ('candidate', 'original')
                for variant in order:
                    plan = json.loads(psql(f'EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) {queries[variant]}'))
                    filename = f"{case['id']}.{variant}.{repeat+1}.plan.json"
                    (args.output / filename).write_text(json.dumps(plan, indent=2) + '\n')
                    root = plan[0]['Plan']
                    entry['variants'][variant]['runs'].append({
                        'execution_ms': plan[0]['Execution Time'], 'planning_ms': plan[0]['Planning Time'],
                        'estimated_total_cost': root['Total Cost'], 'plan_rows': root['Plan Rows'],
                        'actual_rows': root['Actual Rows'], 'shared_hit_blocks': root.get('Shared Hit Blocks', 0),
                        'shared_read_blocks': root.get('Shared Read Blocks', 0),
                        'temp_read_blocks': root.get('Temp Read Blocks', 0),
                        'temp_written_blocks': root.get('Temp Written Blocks', 0), 'plan_file': filename})
            for variant in entry['variants'].values():
                times = sorted(r['execution_ms'] for r in variant['runs'])
                variant['median_execution_ms'] = statistics.median(times)
                variant['p95_execution_ms'] = times[math.ceil(.95*len(times))-1]
                variant['min_execution_ms'], variant['max_execution_ms'] = times[0], times[-1]
            report['queries'].append(entry)
            print(f"{case['id']}: equivalent, {row_count} rows, plans saved", flush=True)
        report['status'] = 'passed'
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        raise
    finally:
        report['finished_at_utc'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        # Only the UUID-named container created by this run is removed.
        subprocess.run(['docker', 'rm', '--force', name], capture_output=True, timeout=30)


if __name__ == '__main__':
    main()
