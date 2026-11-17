#!/usr/bin/env python3
"""Run a disposable PostgreSQL + HypoPG planning experiment.

The scenario proves only a planner-level effect: HypoPG indexes are session-local
and may be considered by EXPLAIN, never by EXPLAIN ANALYZE.  It starts an
ephemeral container, seeds synthetic data, records both JSON plans and deletes
the container even if the run fails.
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


# This image uses the regular PostgreSQL container entrypoint and bundles HypoPG.
IMAGE = "ghcr.io/digib/postgres:18"
QUERY = "SELECT count(*), sum(total_cents) FROM lab_orders WHERE customer_id = 4242 AND status = 'COMPLETED'"
INDEX_DDL = "CREATE INDEX ON lab_orders (customer_id) INCLUDE (total_cents) WHERE status = 'COMPLETED'"


def run(command: list[str], *, input_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, input=input_text, text=True, capture_output=True, check=check)


def psql(container: str, sql: str) -> str:
    completed = run([
        "docker", "exec", "-i", container, "psql", "-U", "vigil", "-d", "vigil_lab",
        "-v", "ON_ERROR_STOP=1", "-X", "-q", "-t", "-A",
    ], input_text=sql)
    return completed.stdout.strip()


def psql_session(container: str, sql: str) -> str:
    """Run multiple statements through one session (HypoPG indexes are session-local)."""
    return psql(container, sql)


def plan_summary(plan_document: dict) -> dict:
    plan = plan_document["Plan"]
    return {
        "nodeType": plan["Node Type"],
        "totalCost": plan["Total Cost"],
        "planRows": plan["Plan Rows"],
        "indexName": find_index_name(plan),
    }


def find_index_name(node: dict) -> str | None:
    if "Index Name" in node:
        return node["Index Name"]
    for child in node.get("Plans", []):
        name = find_index_name(child)
        if name:
            return name
    return None


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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Directory that will receive plans and result.json")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    container = f"vigil-hypopg-{uuid.uuid4().hex[:10]}"

    try:
        run([
            "docker", "run", "-d", "--rm", "--name", container,
            "-e", "POSTGRES_USER=vigil", "-e", "POSTGRES_PASSWORD=vigil",
            "-e", "POSTGRES_DB=vigil_lab", IMAGE,
        ])
        wait_until_ready(container)
        session_output = psql_session(container, f"""
            CREATE EXTENSION hypopg;
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
            ANALYZE lab_orders;
            SELECT 'VIGIL_BEFORE';
            EXPLAIN (FORMAT JSON) {QUERY};
            SELECT 'VIGIL_INDEX';
            SELECT indexname FROM hypopg_create_index($${INDEX_DDL}$$);
            SELECT 'VIGIL_AFTER';
            EXPLAIN (FORMAT JSON) {QUERY};
            SELECT 'VIGIL_INDEXES';
            SELECT index_name FROM hypopg_list_indexes;
        """)
        before = json.loads(section(session_output, "VIGIL_BEFORE", "VIGIL_INDEX"))[0]
        hypothetical = section(session_output, "VIGIL_INDEX", "VIGIL_AFTER")
        after = json.loads(section(session_output, "VIGIL_AFTER", "VIGIL_INDEXES"))[0]
        indexes = section(session_output, "VIGIL_INDEXES", None)

        (output / "plan-before.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
        (output / "plan-after.json").write_text(json.dumps(after, indent=2), encoding="utf-8")
        result = {
            "schemaVersion": 1,
            "executedAtUtc": datetime.now(timezone.utc).isoformat(),
            "environment": {"image": IMAGE, "database": "ephemeral Docker PostgreSQL", "data": "synthetic"},
            "safety": {
                "targetDatabaseAccessed": False,
                "physicalIndexCreated": False,
                "explainAnalyzeUsedWithHypoPG": False,
                "containerRemovalRequested": True,
            },
            "query": QUERY,
            "hypotheticalIndexDdl": INDEX_DDL,
            "registeredHypotheticalIndex": hypothetical,
            "visibleHypotheticalIndexes": indexes.splitlines(),
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
