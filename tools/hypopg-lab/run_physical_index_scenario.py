#!/usr/bin/env python3
"""Measure a disposable physical-index experiment with EXPLAIN ANALYZE.

The database is an ephemeral Docker container populated only with deterministic
synthetic rows.  A physical partial covering index is created after the first
measurement, then the container is removed.  The monitored target is never
contacted.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


IMAGE = "postgres:16.4"
QUERY = "SELECT count(*), sum(total_cents) FROM lab_orders WHERE customer_id = 4242 AND status = 'COMPLETED'"
INDEX_DDL = "CREATE INDEX lab_orders_customer_completed_cover_idx ON lab_orders (customer_id) INCLUDE (total_cents) WHERE status = 'COMPLETED'"


def run(command: list[str], *, input_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, input=input_text, text=True, capture_output=True, check=check)


def psql(container: str, sql: str) -> str:
    completed = run([
        "docker", "exec", "-i", container, "psql", "-U", "vigil", "-d", "vigil_lab",
        "-v", "ON_ERROR_STOP=1", "-X", "-q", "-t", "-A",
    ], input_text=sql)
    return completed.stdout.strip()


def section(output: str, start: str, end: str | None) -> str:
    after_start = output.split(start + "\n", 1)[1]
    return after_start.split("\n" + end, 1)[0].strip() if end else after_start.strip()


def wait_until_ready(container: str) -> None:
    for _ in range(40):
        ready = run(["docker", "exec", container, "pg_isready", "-U", "vigil", "-d", "vigil_lab"], check=False)
        if ready.returncode == 0:
            return
        time.sleep(0.5)
    raise RuntimeError("PostgreSQL container did not become ready within 20 seconds.")


def find_node(node: dict, name: str) -> dict | None:
    if node.get("Node Type") == name:
        return node
    for child in node.get("Plans", []):
        found = find_node(child, name)
        if found:
            return found
    return None


def plan_summary(document: dict) -> dict:
    plan = document["Plan"]
    index_node = find_node(plan, "Index Only Scan") or find_node(plan, "Bitmap Index Scan") or find_node(plan, "Index Scan")
    return {
        "nodeType": plan["Node Type"],
        "totalCost": plan["Total Cost"],
        "actualTotalTimeMs": plan.get("Actual Total Time"),
        "executionTimeMs": document.get("Execution Time"),
        "planningTimeMs": document.get("Planning Time"),
        "sharedHitBlocks": plan.get("Shared Hit Blocks"),
        "sharedReadBlocks": plan.get("Shared Read Blocks"),
        "indexName": index_node.get("Index Name") if index_node else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Directory for plans and result.json")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    container = f"vigil-physical-index-{uuid.uuid4().hex[:10]}"

    try:
        run([
            "docker", "run", "-d", "--rm", "--name", container,
            "-e", "POSTGRES_USER=vigil", "-e", "POSTGRES_PASSWORD=vigil",
            "-e", "POSTGRES_DB=vigil_lab", IMAGE,
        ])
        wait_until_ready(container)
        seed = """
            CREATE TABLE lab_orders (
              id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
              customer_id integer NOT NULL,
              status text NOT NULL,
              total_cents integer NOT NULL
            );
            INSERT INTO lab_orders (customer_id, status, total_cents)
            SELECT ((g - 1) % 10000) + 1,
                   CASE WHEN g % 5 = 0 THEN 'PENDING' ELSE 'COMPLETED' END,
                   1000 + (g % 9000)
            FROM generate_series(1, 200000) AS g;
            VACUUM ANALYZE lab_orders;
        """
        psql(container, seed)
        before = json.loads(psql(container, f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {QUERY};"))[0]
        psql(container, f"{INDEX_DDL}; VACUUM ANALYZE lab_orders;")
        after = json.loads(psql(container, f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {QUERY};"))[0]
        (output / "plan-before-physical.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
        (output / "plan-after-physical.json").write_text(json.dumps(after, indent=2), encoding="utf-8")
        result = {
            "schemaVersion": 1,
            "executedAtUtc": datetime.now(timezone.utc).isoformat(),
            "environment": {"image": IMAGE, "database": "ephemeral Docker PostgreSQL", "data": "200000 deterministic synthetic rows"},
            "safety": {
                "targetDatabaseAccessed": False,
                "physicalIndexCreated": True,
                "explainAnalyzeUsed": True,
                "containerRemovalRequested": True,
            },
            "query": QUERY,
            "physicalIndexDdl": INDEX_DDL,
            "before": plan_summary(before),
            "after": plan_summary(after),
        }
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
