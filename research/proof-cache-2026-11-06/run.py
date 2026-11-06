#!/usr/bin/env python3
"""Compare the pinned external proof cache with Bend's ordinary check-only CLI.

This is an opt-in research harness. It never changes the supplied compiler or
the plugin, and it never downloads dependencies or runs a Bend program.
"""

import argparse
import hashlib
import json
import os
import platform
import re
import resource
import statistics
import subprocess
import tempfile
import time
from pathlib import Path


COMPILER_COMMIT = "624dd9f4607cd29b0871a9b96d413bfb11e92f52"
BUN_REVISION = "1.4.4+38afee6c8"
STATS = re.compile(rb"bend-cache: checked (\d+), skipped (\d+), traces kept (\d+)\n")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def command_output(args):
    return subprocess.check_output(args, text=True).strip()


def sources(**files):
    return {name.replace("__", "."): text for name, text in files.items()}


def scenarios():
    library = "import Base\n\ndef marker() -> Nat:\n  23n\n\ndef neighbor() -> Nat:\n  41n\n"
    client = (
        "import Base\nimport ./Library.bend as Library\n\n"
        "def marker_is_expected() -> {Library.marker == 23n : Nat}:\n  {==}\n"
    )
    laws = (
        "import Base\n\nlaw quota: Nat\n\n"
        "law quota_refl: {quota == quota : Nat}\n"
    )
    proof = (
        "import Base\nimport ./LAWS.bend as Contract\n\n"
        "def Contract.quota():\n  23n\n\n"
        "def Contract.quota_refl():\n  {==}\n"
    )
    load_root = (
        "import Base\nimport ./Implementation.bend as Implementation\n"
        "import ./Consumer.bend as Consumer\n\ndef root() -> Nat:\n  0n\n"
    )
    risky = (
        "import Base\n\n@unsafe\ndef input_value() -> Nat:\n  31n\n\n"
        "def copy_value() -> Nat:\n  input_value()\n"
    )
    foreign = (
        "import Base\n\nlaw send_event: IO(Unit)\n\n"
        "def send_event():\n  import \"./not-executed.js\"\n\n"
        "def main() -> IO(Unit):\n  send_event()\n"
    )
    # These programs and transitions are original witnesses of the architecture's
    # root, completeness and reliance contracts, not copies of upstream tests.
    return [
        {
            "name": "dependency-edit",
            "files": sources(Library__bend=library, Check__bend=client),
            "entry": "Check.bend",
            "expected": "complete",
            "changes": [
                ("break-referenced-body", "Library.bend", library.replace("23n", "24n"), "failed"),
                ("restore-body", "Library.bend", library, "complete"),
                ("comment-only", "Library.bend", "# unchanged meaning\n" + library, "complete"),
                ("remove-imported-file", "Library.bend", None, "failed"),
                ("restore-imported-file", "Library.bend", library, "complete"),
            ],
        },
        {
            "name": "law-fill-roots",
            "files": sources(
                LAWS__bend=laws,
                PROOF__bend=proof,
                Alternative__bend=proof.replace("23n", "29n"),
            ),
            "entry": "PROOF.bend",
            "expected": "complete",
            "changes": [
                ("drop-proof-fill", "PROOF.bend", proof.split("\ndef Contract.quota_refl")[0] + "\n", "incomplete"),
                ("restore-proof-fill", "PROOF.bend", proof, "complete"),
                ("drop-value-fill", "PROOF.bend", proof.replace("def Contract.quota():\n  23n\n\n", ""), "incomplete"),
                ("restore-value-fill", "PROOF.bend", proof, "complete"),
            ],
            "other_entry": "Alternative.bend",
        },
        {
            "name": "fill-load-order",
            "files": sources(
                Contract__bend="import Base\n\nlaw threshold: Nat\n",
                Implementation__bend=(
                    "import Base\nimport ./Contract.bend as C\n\n"
                    "def C.threshold():\n  17n\n"
                ),
                Consumer__bend=(
                    "import Base\nimport ./Contract.bend as C\n\n"
                    "def observes_fill() -> {C.threshold == 17n : Nat}:\n  {==}\n"
                ),
                Main__bend=load_root,
            ),
            "entry": "Main.bend",
            "expected": "complete",
            "changes": [
                (
                    "consumer-before-fill",
                    "Main.bend",
                    load_root.replace(
                        "import ./Implementation.bend as Implementation\nimport ./Consumer.bend as Consumer",
                        "import ./Consumer.bend as Consumer\nimport ./Implementation.bend as Implementation",
                    ),
                    "failed",
                ),
                ("restore-load-order", "Main.bend", load_root, "complete"),
            ],
        },
        {
            "name": "unsafe-reliance",
            "files": sources(Unsafe__bend=risky),
            "entry": "Unsafe.bend",
            "expected": "unsafe",
            "changes": [
                ("remove-unsafe", "Unsafe.bend", risky.replace("@unsafe\n", ""), "complete"),
                ("restore-unsafe", "Unsafe.bend", risky, "unsafe"),
            ],
        },
        {
            "name": "foreign-reliance",
            "files": sources(Foreign__bend=foreign),
            "entry": "Foreign.bend",
            "expected": "foreign",
            "changes": [
                ("remove-foreign-fill", "Foreign.bend", foreign.replace('def send_event():\n  import "./not-executed.js"\n\n', ""), "failed"),
                ("restore-foreign-fill", "Foreign.bend", foreign, "foreign"),
            ],
        },
        {
            "name": "check-only",
            "files": sources(
                Main__bend=(
                    "import Base\n\ndef main() -> IO(Unit):\n"
                    "  do IO<Unit>:\n    IO.print(\"CACHE_PROBE_MAIN_MUST_NOT_RUN\")\n"
                ),
            ),
            "entry": "Main.bend",
            "expected": "complete",
            "changes": [],
        },
    ]


def classify(code, stdout, stderr):
    output = stdout + stderr
    if code == 0 and b"ALL PROOFS CHECK\n" in output:
        return "complete"
    if code == 1 and b"defs that depend on @unsafe:" in output:
        return "unsafe"
    if code == 1 and b"defs that depend on foreign effects:" in output:
        return "foreign"
    if code == 1 and re.search(rb"Error: [1-9][0-9]* TODO", output):
        return "incomplete"
    if code == 1:
        return "failed"
    return "unavailable"


class Experiment:
    def __init__(self, compiler, bun, output, workspace):
        self.compiler = compiler
        self.bun = bun
        self.output = output
        self.workspace = workspace
        self.rows = []
        self.failures = []
        self.env = os.environ.copy()
        self.env.update(BEND_NO_TELEMETRY="1", NO_COLOR="1")
        # Only local imports and compiler-owned Base occur in the generated books.
        # Discard inherited optional compiler/cache settings for repeatability.
        for name in ("BEND_DIR", "BEND_CACHE", "BEND_BASE", "BEND_ORIGIN"):
            self.env.pop(name, None)

    def write_files(self, directory, files):
        directory.mkdir(parents=True, exist_ok=True)
        for name, text in files.items():
            (directory / name).write_text(text, encoding="utf-8")

    def run(self, name, mode, directory, entry, cache, threshold="0", stats=True):
        if mode == "plain":
            command = [str(self.bun), str(self.compiler / "bend2/main.ts"), entry, "--check-only"]
        else:
            command = [
                str(self.bun), str(self.compiler / "tools/bend-cache/src/cache.ts"), entry,
                "--cache", str(cache), "--bend", str(self.compiler / "bend2"),
                "--min-ms", threshold,
            ] + (["--stats"] if stats else [])
        before = resource.getrusage(resource.RUSAGE_CHILDREN)
        started = time.perf_counter_ns()
        try:
            result = subprocess.run(
                command, cwd=directory, env=self.env, capture_output=True, timeout=45,
            )
            code, stdout, stderr = result.returncode, result.stdout, result.stderr
        except subprocess.TimeoutExpired as error:
            code, stdout, stderr = 124, error.stdout or b"", error.stderr or b""
        elapsed = (time.perf_counter_ns() - started) / 1_000_000
        after = resource.getrusage(resource.RUSAGE_CHILDREN)
        suffix = f"{len(self.rows):03d}-{name}-{mode}"
        with (self.output / "outputs.jsonl").open("a", encoding="utf-8") as raw:
            raw.write(json.dumps({
                "id": suffix, "stdout": stdout.decode("utf-8"), "stderr": stderr.decode("utf-8"),
            }, ensure_ascii=False) + "\n")
        counts = STATS.findall(stderr) if mode == "cached" and stats else []
        # Only the explicitly requested tool statistics are excluded from exact
        # comparison; warnings, compiler diagnostics and all stdout remain exact.
        comparable_stderr = STATS.sub(b"", stderr) if counts else stderr
        row = {
            "name": name, "mode": mode, "command": command, "exit": code,
            "wall_ms": elapsed,
            "child_user_ms": (after.ru_utime - before.ru_utime) * 1000,
            "child_system_ms": (after.ru_stime - before.ru_stime) * 1000,
            "stdout_sha256": digest(stdout), "stderr_sha256": digest(stderr),
            "comparable_stderr_sha256": digest(comparable_stderr),
            "classification": classify(code, stdout, comparable_stderr),
            "output_record": suffix,
            "stats": dict(zip(("checked", "skipped", "kept"), map(int, counts[0]))) if len(counts) == 1 else None,
            "sources": {str(p.relative_to(directory)): digest(p.read_bytes()) for p in sorted(directory.glob("*.bend"))},
        }
        if cache.is_file():
            store_bytes = cache.read_bytes()
            row["cache_sha256"] = digest(store_bytes)
            row["cache_trace_count"] = len(json.loads(store_bytes)["traces"])
        self.rows.append(row)
        if b"CACHE_PROBE_MAIN_MUST_NOT_RUN" in stdout:
            self.failures.append(f"{name}: program main ran")
        if code < 0 or code == 124 or code == 2:
            self.failures.append(f"{name}/{mode}: unavailable exit {code}")
        return row

    def compare(self, name, directory, entry, cache, expected, order=("plain", "cached"), threshold="0", stats=True):
        rows = {mode: self.run(name, mode, directory, entry, cache, threshold, stats) for mode in order}
        plain, cached = rows["plain"], rows["cached"]
        equal = all(plain[key] == cached[key] for key in ("exit", "stdout_sha256", "comparable_stderr_sha256"))
        for row in rows.values():
            row["matches_plain"] = equal
            if row["classification"] != expected:
                self.failures.append(f"{name}/{row['mode']}: expected {expected}, got {row['classification']}")
        if not equal:
            self.failures.append(f"{name}: cache differs from plain check")
        return plain, cached

    def correctness(self):
        checks = 0
        total_skipped = 0
        for scenario in scenarios():
            directory = self.workspace / scenario["name"]
            cache = directory / "traces.json"
            self.write_files(directory, scenario["files"])
            # Preserve initial sources beside the logs; transitions are explicit
            # in this script and the per-run manifest hashes every source.
            self.write_files(self.output / "fixtures" / scenario["name"], scenario["files"])
            for stage in ("cold", "warm"):
                _, cached = self.compare(
                    f"{scenario['name']}-{stage}", directory, scenario["entry"], cache, scenario["expected"],
                )
                checks += 1
                if stage == "warm":
                    skipped = (cached["stats"] or {}).get("skipped", 0)
                    total_skipped += skipped
                    if skipped == 0:
                        self.failures.append(f"{scenario['name']}: warm run skipped no checks")
                    if (cached["stats"] or {}).get("checked", -1) != 0:
                        self.failures.append(f"{scenario['name']}: forced-trace warm run did not reuse every check")
            if "other_entry" in scenario:
                self.compare(
                    f"{scenario['name']}-other-root", directory, scenario["other_entry"], cache, "complete",
                )
                checks += 1
            for stage, name, replacement, expected in scenario["changes"]:
                at = directory / name
                if replacement is None:
                    at.unlink()
                else:
                    at.write_text(replacement, encoding="utf-8")
                self.compare(f"{scenario['name']}-{stage}", directory, scenario["entry"], cache, expected)
                checks += 1
        return {"comparison_pairs": checks, "warm_skipped_entries": total_skipped}

    def performance(self, pairs):
        directory = self.workspace / "performance"
        cache = directory / "traces.json"
        source = (
            "import Base\n\ndef rebuild(size: Nat) -> Nat:\n  match size:\n"
            "    case 0n:\n      0n\n    case 1n+rest:\n      1n+rebuild(rest)\n\n"
        ) + "\n".join(
            f"def roundtrip_{i}() -> {{rebuild({150+i}n) == {150+i}n : Nat}}:\n  {{==}}\n"
            for i in range(32)
        )
        self.write_files(directory, {"Workload.bend": source})
        self.write_files(self.output / "fixtures/performance", {"Workload.bend": source})
        self.compare("performance-cold", directory, "Workload.bend", cache, "complete", threshold="0.2")
        _, warm = self.compare("performance-warm-proof", directory, "Workload.bend", cache, "complete", threshold="0.2")
        if (warm["stats"] or {}).get("skipped", 0) == 0:
            self.failures.append("performance: default-threshold warm run skipped no checks")
        observations = {"plain": [], "cached": []}
        for i in range(pairs):
            order = ("plain", "cached") if i % 2 == 0 else ("cached", "plain")
            plain, cached = self.compare(
                f"performance-pair-{i}", directory, "Workload.bend", cache, "complete",
                order=order, threshold="0.2", stats=False,
            )
            for row in (plain, cached):
                observations[row["mode"]].append(row["wall_ms"])
        return {
            "pairs": pairs, "min_ms": 0.2, "generated_proofs": 32,
            "observed_wall_ms": {
                mode: {"median": statistics.median(values), "min": min(values), "max": max(values)}
                for mode, values in observations.items()
            },
            "verdict": "INCONCLUSIVE: observations only; shared host, no isolated limiter profile or IDE end-to-end measurement",
        }

    def cache_trust(self):
        directory = self.workspace / "cache-trust"
        cache = directory / "traces.json"
        valid = "import Base\n\ndef alleged() -> {6n == 6n : Nat}:\n  {==}\n"
        invalid = valid.replace("6n == 6n", "6n == 7n")
        self.write_files(directory, {"Claim.bend": valid})
        self.write_files(self.output / "fixtures/cache-trust", {"Claim.bend": valid})
        self.compare("cache-trust-prime", directory, "Claim.bend", cache, "complete")
        store = json.loads(cache.read_text(encoding="utf-8"))
        trace = store["traces"].get("alleged#0f")
        if trace is None:
            self.failures.append("cache-trust: expected own declaration trace was not created")
            return {"observation": "INCONCLUSIVE: own trace missing"}
        # Deliberately break the cache's trust premise, not the compiler source:
        # a syntactically valid trace with no recorded reads always matches.
        trace["d"] = []
        cache.write_text(json.dumps(store), encoding="utf-8")
        (self.output / "cache-trust-entry.json").write_text(
            json.dumps({"key": "alleged#0f", "trace": trace}, indent=2) + "\n", encoding="utf-8",
        )
        (directory / "Claim.bend").write_text(invalid, encoding="utf-8")
        plain = self.run("cache-trust-tampered", "plain", directory, "Claim.bend", cache)
        cached = self.run("cache-trust-tampered", "cached", directory, "Claim.bend", cache)
        if plain["classification"] != "failed":
            self.failures.append("cache-trust: plain compiler did not reject the false equality")
        return {
            "scope": "separate deliberate local-cache tamper, excluded from normal invalidation equivalence",
            "plain": plain["classification"], "cached": cached["classification"],
            "observation": "FALSE PASS from untrusted cache" if cached["classification"] == "complete" else "cache rejected invalid proof",
            "mutation": "replace only alleged#0f trace.d with [] after warming; change equality right side 6n to 7n",
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", required=True, type=Path, help="clean Bend 2.0.40 checkout")
    parser.add_argument("--bun", required=True, type=Path, help=f"Bun {BUN_REVISION}")
    parser.add_argument("--output", required=True, type=Path, help="new directory for manifests and exact output")
    parser.add_argument("--performance-pairs", type=int, default=5)
    args = parser.parse_args()
    compiler, bun, output = args.compiler.resolve(), args.bun.resolve(), args.output.resolve()
    if args.performance_pairs < 5:
        parser.error("at least five alternating measurement pairs are required")
    if output.exists():
        parser.error("output directory already exists; choose a new path to preserve evidence")
    if output.is_relative_to(compiler):
        parser.error("output must be outside the supplied compiler checkout")
    commit = command_output(["git", "-C", str(compiler), "rev-parse", "HEAD"])
    clean = command_output(["git", "-C", str(compiler), "status", "--porcelain"])
    revision = command_output([str(bun), "--revision"])
    if commit != COMPILER_COMMIT or clean or revision != BUN_REVISION:
        parser.error(f"expected clean compiler {COMPILER_COMMIT} and Bun {BUN_REVISION}; got {commit}, dirty={bool(clean)}, Bun {revision}")
    output.mkdir(parents=True)
    metadata = {
        "compiler_commit": commit, "bun_revision": revision, "bun_sha256": digest(bun.read_bytes()),
        "platform": platform.platform(), "machine": platform.machine(), "logical_cpus": os.cpu_count(),
        "load_average_start": os.getloadavg(), "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "hypothesis": "Only consider a production prototype if cold/warm and all edited results exactly equal uncached checks and warm checks actually skip work; timing alone cannot authorize adoption.",
        "compiler_files": {name: digest((compiler / name).read_bytes()) for name in (
            "bend2/bend.ts", "bend2/main.ts", "bend2/base.bend", "tools/bend-cache/src/cache.ts",
        )},
    }
    with tempfile.TemporaryDirectory(prefix="bend-idea-cache-probe-") as temporary:
        experiment = Experiment(compiler, bun, output, Path(temporary))
        correctness = experiment.correctness()
        performance = experiment.performance(args.performance_pairs)
        cache_trust = experiment.cache_trust()
        metadata.update(
            correctness=correctness, performance=performance,
            cache_trust=cache_trust,
            load_average_end=os.getloadavg(), failures=experiment.failures,
            normal_equivalence="PASS" if not experiment.failures else "FAIL",
        )
        (output / "runs.json").write_text(json.dumps(experiment.rows, indent=2) + "\n", encoding="utf-8")
    unchanged = command_output(["git", "-C", str(compiler), "status", "--porcelain"]) == clean
    if not unchanged:
        metadata["failures"].append("supplied compiler checkout changed during experiment")
        metadata["normal_equivalence"] = "FAIL"
    metadata["compiler_unchanged"] = unchanged
    (output / "summary.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2))
    return 0 if not metadata["failures"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
