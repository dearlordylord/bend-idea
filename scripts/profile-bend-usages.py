#!/usr/bin/env python3
"""Measure native Find Usages in independent fixture JVMs; retain each raw run."""

import argparse
import csv
import hashlib
import json
import os
import platform
import shutil
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--samples", type=int, default=10)
    parser.add_argument("--output", type=Path, default=Path("build/reports/usages-profile"))
    args = parser.parse_args()
    if not 1 <= args.runs <= 100 or not 1 <= args.samples <= 50:
        parser.error("runs must be 1..100 and samples must be 1..50")
    project = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    # Fail rather than mix old profiles with a new source revision.
    output.mkdir(parents=True, exist_ok=False)
    diff = subprocess.check_output(["git", "diff", "HEAD"], cwd=project)
    measured_files = subprocess.check_output(
        ["git", "ls-files", "src/main", "src/test", "build.gradle.kts", "gradle.properties",
         "ci/bend-test-toolchain.properties"], cwd=project, text=True
    ).splitlines()
    metadata = {
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=project, text=True).strip(),
        "tracked_diff_sha256": hashlib.sha256(diff).hexdigest(),
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "measured_files_sha256": {name: hashlib.sha256((project / name).read_bytes()).hexdigest()
                                  for name in measured_files},
        "platform": platform.platform(),
        "runs": args.runs,
        "samples": args.samples,
        "environment": {name: os.environ.get(name) for name in
                        ("JAVA_HOME", "BEND_TEST_COMPILER_DIR", "BEND_TEST_BUN")},
    }
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    workloads = [
        ("dense49", 24, 24, 8, False),
        ("sparse273", 16, 256, 4, False),
        ("sparse1041", 16, 1024, 4, False),
        ("shadowed1041", 16, 1024, 4, True),
    ]
    report = project / "build/reports/source-query-profile.csv"
    combined = []
    fields = None
    for run in range(1, args.runs + 1):
        # Rotate workload order to reduce a fixed order's host/JIT confounding.
        start = (run - 1) % len(workloads)
        for name, importers, unrelated, calls, shadowed in workloads[start:] + workloads[:start]:
            stem = f"{name}-run{run}"
            command = [str(project / "gradlew"), "test", "-PbendSourceProfile=true",
                       "--tests", "*testFindUsagesMeasurements",
                       f"-PbendSourceProfile.importers={importers}",
                       f"-PbendSourceProfile.unrelated={unrelated}",
                       f"-PbendSourceProfile.calls={calls}",
                       f"-PbendSourceProfile.shadowed={str(shadowed).lower()}",
                       f"-PbendSourceProfile.samples={args.samples}",
                       f"-PbendSourceProfile.run={run}"]
            print(f"Profiling {stem}: {1 + importers + unrelated} files", flush=True)
            (output / f"{stem}.command.json").write_text(json.dumps(command, indent=2) + "\n")
            with (output / f"{stem}.log").open("w") as log:
                subprocess.run(command, cwd=project, stdout=log, stderr=subprocess.STDOUT,
                               check=True, timeout=900)
            shutil.copyfile(report, output / f"{stem}.csv")
            shutil.copyfile(report.with_suffix(".properties"), output / f"{stem}.properties")
            with report.open(newline="") as source:
                reader = csv.DictReader(source)
                fields = reader.fieldnames
                rows = list(reader)
            expected = importers * calls
            measured = [row for row in rows if row["phase"] not in
                        ("fixture-build", "pre-canceled-request", "scan-cancel-after-first-capture")]
            if len(measured) != 3 * (1 + args.samples) or any(
                int(row["result_count"]) != expected for row in measured
            ):
                raise RuntimeError(f"Incomplete or incorrect profile: {stem}")
            combined.extend(rows)
    with (output / "combined.csv").open("w", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=fields)
        writer.writeheader()
        writer.writerows(combined)
    print(f"Raw profiles: {output}", flush=True)


if __name__ == "__main__":
    main()
