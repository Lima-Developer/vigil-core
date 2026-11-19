#!/usr/bin/env python3
"""Measure PostgreSQL workload overhead from Vigil's read-only telemetry queries.

The experiment creates an ephemeral PostgreSQL container, initializes pgbench,
and interleaves equal pgbench runs without and with the SQL executed by the
Vigil telemetry adapters. It never connects to an external target database.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


IMAGE = "postgres:16.4"
COUNTERS_SQL = """BEGIN READ ONLY;
SELECT stats_reset, dealloc FROM pg_stat_statements_info;
SELECT dbid, userid, queryid, toplevel, calls, total_exec_time, rows,
       shared_blks_hit, shared_blks_read, temp_blks_read, temp_blks_written
  FROM pg_stat_statements
 WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
   AND query IS NOT NULL
   AND ltrim(query) ~* '^(select|with)\\y'
   AND query NOT ILIKE '%pg_stat_statements%'
 ORDER BY dbid, userid, queryid, toplevel;
ROLLBACK;"""
ACTIVITY_SQL = """BEGIN READ ONLY;
SELECT count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active'),
       count(*) FILTER (WHERE pid <> pg_backend_pid() AND wait_event IS NOT NULL),
       count(*) FILTER (WHERE pid <> pg_backend_pid() AND cardinality(pg_blocking_pids(pid)) > 0)
  FROM pg_stat_activity;
ROLLBACK;"""
TPS_PATTERN = re.compile(r"tps = ([0-9.]+) \(without initial connection time\)")
LATENCY_PATTERN = re.compile(r"latency average = ([0-9.]+) ms")


def run(command: list[str], *, input_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, input=input_text, text=True, capture_output=True, check=check)


def psql(container: str, sql: str) -> str:
    completed = run([
        "docker", "exec", "-i", container, "psql", "-U", "vigil", "-d", "vigil_lab",
        "-v", "ON_ERROR_STOP=1", "-X", "-q", "-t", "-A",
    ], input_text=sql)
    return completed.stdout


def wait_until_ready(container: str) -> None:
    for _ in range(40):
        ready = run(["docker", "exec", container, "pg_isready", "-U", "vigil", "-d", "vigil_lab"], check=False)
        if ready.returncode == 0:
            return
        time.sleep(0.5)
    raise RuntimeError("PostgreSQL container did not become ready within 20 seconds.")


def parse_pgbench(output: str) -> dict[str, float]:
    tps = TPS_PATTERN.search(output)
    latency = LATENCY_PATTERN.search(output)
    if not tps or not latency:
        raise RuntimeError(f"Could not parse pgbench output:\n{output}")
    return {"tps": float(tps.group(1)), "latencyAverageMs": float(latency.group(1))}


def run_pgbench(container: str, clients: int, threads: int, seconds: int) -> tuple[dict[str, float], str]:
    started = time.perf_counter()
    completed = run([
        "docker", "exec", container, "pgbench", "-U", "vigil", "-d", "vigil_lab",
        "-M", "prepared", "-c", str(clients), "-j", str(threads), "-T", str(seconds),
    ])
    metrics = parse_pgbench(completed.stdout)
    metrics["wallClockSeconds"] = round(time.perf_counter() - started, 3)
    return metrics, completed.stdout


def collect_until_stopped(container: str, interval_seconds: float, stop: threading.Event, samples: list[dict[str, float]]) -> None:
    while not stop.is_set():
        started = time.perf_counter()
        try:
            psql(container, COUNTERS_SQL)
            psql(container, ACTIVITY_SQL)
            samples.append({"durationMs": round((time.perf_counter() - started) * 1000, 3)})
        except subprocess.CalledProcessError as failure:
            samples.append({"error": (failure.stderr or failure.stdout or str(failure)).strip()})
        stop.wait(interval_seconds)


def median(values: list[float]) -> float:
    return round(statistics.median(values), 3)


def percent_change(reference: float, observed: float) -> float:
    return round(((observed - reference) / reference) * 100, 3)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Directory for raw output and result.json")
    parser.add_argument("--scale", type=int, default=5, help="pgbench scale factor; default: 5")
    parser.add_argument("--clients", type=int, default=8, help="Concurrent pgbench clients; default: 8")
    parser.add_argument("--threads", type=int, default=4, help="pgbench worker threads; default: 4")
    parser.add_argument("--seconds", type=int, default=30, help="Duration of each measured run; default: 30")
    parser.add_argument("--rounds", type=int, default=3, help="Interleaved baseline/collector pairs; default: 3")
    parser.add_argument("--collector-interval-seconds", type=float, default=1.0,
                        help="Interval between collector cycles; default: 1")
    args = parser.parse_args()
    if min(args.scale, args.clients, args.threads, args.seconds, args.rounds) <= 0 or args.collector_interval_seconds <= 0:
        parser.error("scale, clients, threads, seconds, rounds and collector interval must be positive")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    container = f"vigil-telemetry-overhead-{uuid.uuid4().hex[:10]}"
    baselines: list[dict[str, float]] = []
    collected: list[dict[str, float]] = []
    raw_runs: list[dict[str, object]] = []

    try:
        run([
            "docker", "run", "-d", "--rm", "--name", container,
            "-e", "POSTGRES_USER=vigil", "-e", "POSTGRES_PASSWORD=vigil", "-e", "POSTGRES_DB=vigil_lab",
            IMAGE, "-c", "shared_preload_libraries=pg_stat_statements", "-c", "pg_stat_statements.track=all",
        ])
        wait_until_ready(container)
        psql(container, "CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset();")
        run(["docker", "exec", container, "pgbench", "-U", "vigil", "-d", "vigil_lab", "-i", "-s", str(args.scale)])
        # Warm cache and validate pgbench before beginning the paired measurements.
        run_pgbench(container, args.clients, args.threads, min(5, args.seconds))

        for round_number in range(1, args.rounds + 1):
            # Alternate order to reduce systematic cache or temperature bias.
            conditions = ("baseline", "collector") if round_number % 2 else ("collector", "baseline")
            for condition in conditions:
                if condition == "baseline":
                    metrics, raw = run_pgbench(container, args.clients, args.threads, args.seconds)
                    baselines.append(metrics)
                    raw_runs.append({"round": round_number, "condition": condition, "metrics": metrics, "pgbench": raw})
                    continue

                stop = threading.Event()
                samples: list[dict[str, float]] = []
                worker = threading.Thread(target=collect_until_stopped,
                                          args=(container, args.collector_interval_seconds, stop, samples), daemon=True)
                worker.start()
                metrics, raw = run_pgbench(container, args.clients, args.threads, args.seconds)
                stop.set()
                worker.join(timeout=10)
                metrics["collectorCycles"] = len(samples)
                collected.append(metrics)
                raw_runs.append({"round": round_number, "condition": condition, "metrics": metrics,
                                 "collectorSamples": samples, "pgbench": raw})

        base_tps = median([sample["tps"] for sample in baselines])
        collector_tps = median([sample["tps"] for sample in collected])
        base_latency = median([sample["latencyAverageMs"] for sample in baselines])
        collector_latency = median([sample["latencyAverageMs"] for sample in collected])
        collection_durations = [sample["durationMs"] for item in raw_runs for sample in item.get("collectorSamples", [])
                                if "durationMs" in sample]
        result = {
            "schemaVersion": 1,
            "executedAtUtc": datetime.now(timezone.utc).isoformat(),
            "environment": {
                "image": IMAGE,
                "database": "ephemeral Docker PostgreSQL",
                "targetDatabaseAccessed": False,
                "pgStatStatementsEnabled": True,
            },
            "method": {
                "workload": "pgbench built-in prepared workload",
                "comparison": "interleaved baseline and collector runs with alternating order",
                "collector": "read-only SQL equivalent to Vigil PgStatStatementsTelemetryAdapter and PostgresDatabaseActivityAdapter",
                "excluded": "Spring process, TLS connection setup, SQLite persistence and external target access",
            },
            "parameters": {
                "scale": args.scale, "clients": args.clients, "threads": args.threads,
                "secondsPerRun": args.seconds, "rounds": args.rounds,
                "collectorIntervalSeconds": args.collector_interval_seconds,
            },
            "baseline": {"runs": baselines, "medianTps": base_tps, "medianLatencyAverageMs": base_latency},
            "withCollector": {"runs": collected, "medianTps": collector_tps,
                              "medianLatencyAverageMs": collector_latency},
            "overhead": {
                "throughputChangePercent": percent_change(base_tps, collector_tps),
                "latencyChangePercent": percent_change(base_latency, collector_latency),
                "collectorCycles": len(collection_durations),
                "collectorMedianDurationMs": median(collection_durations) if collection_durations else None,
            },
        }
        (output / "pgbench-runs.json").write_text(json.dumps(raw_runs, indent=2), encoding="utf-8")
        (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result, indent=2))
    finally:
        run(["docker", "rm", "-f", container], check=False)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.stderr.write(error.stderr or error.stdout or str(error))
        raise
