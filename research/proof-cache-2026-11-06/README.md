# External proof-cache experiment — 2026-11-06

**Normal invalidation comparisons passed; production adoption is not justified.**
The pinned external `bend-cache` matched ordinary Bend check-only output in all
36 ordinary comparison pairs. A separate deliberate local-cache tamper made the
cache accept a false equality that ordinary Bend rejected. Timing observations
are **inconclusive**, not a measured speedup for Bend IDEA.

This completes the bounded experiment proposed in the November competitor
assessment. It adds no plugin cache, compiler patch, dependency, setting or
background process. The package owners and public contracts are unchanged.

## Inputs and reproduction

- [Bend 2.0.40](https://github.com/bendlang/bend/tree/624dd9f4607cd29b0871a9b96d413bfb11e92f52),
  exact clean source commit `624dd9f4607cd29b0871a9b96d413bfb11e92f52`.
- External tool: that revision's
  [`tools/bend-cache/src/cache.ts`](https://github.com/bendlang/bend/blob/624dd9f4607cd29b0871a9b96d413bfb11e92f52/tools/bend-cache/src/cache.ts).
  The tool's [README](https://github.com/bendlang/bend/blob/624dd9f4607cd29b0871a9b96d413bfb11e92f52/tools/bend-cache/README.md)
  explicitly treats its skip decisions as development-tool judgments; ordinary
  CLI checking and kernel verdicts remain authoritative.
- Bun `1.4.4+38afee6c8`, Linux ARM64 executable SHA-256
  `354c2b049c716aca07b2e5cddb887928ac58950675d1258bb6312083917793b6`.
- Python standard library runner; no package installation is needed.

With the clean checkout and runtime already available, run from the repository:

```sh
python3 research/proof-cache-2026-11-06/run.py \
  --compiler /path/to/clean/bend-2.0.40 \
  --bun /path/to/bun-1.4.4 \
  --output /tmp/bend-cache-new-observation
```

The output directory must be new and outside the supplied compiler checkout.
The runner checks exact identities and compiler cleanliness before running and
checks cleanliness again afterwards. It creates its own temporary books and
cache files, uses only local imports and compiler-owned Base, disables telemetry
and update notices, and gives each subprocess 45 seconds. It never edits the
compiler or preserved reference checkouts. It executes source CLI checks under
Bun, rather than comparing a compiled executable with a source implementation.
No downloading, building or running of a Bend program occurs in the experiment.

## Correctness observations

The ordinary equality predicate compares the exit code, exact stdout bytes and
exact stderr bytes. The only exclusion is the explicitly requested, anchored
`bend-cache: checked ..., skipped ..., traces kept ...` statistics line; warnings
and compiler messages are retained. Normal cache files are never modified by the
runner, except for the explicitly separate trust probe below.

| Original fixture group | Observed transitions |
| --- | --- |
| Imported dependency | Cold/warm success; referenced value edit breaks the equality; restoration succeeds; comment-only edit preserves success; dependency deletion fails; recreation succeeds. |
| Laws, fills and roots | Full proof succeeds; switching to another root with a different value fill succeeds through the shared cache; deleting either proof or value fill reports incompleteness; restoring it succeeds. |
| Fill visibility through load order | A consumer sees a fill supplied by another module without importing that implementation itself. Moving the consumer before the fill fails the equality; restoring import order succeeds. |
| Unsafe reliance | Cold and warm results retain the unsafe failure and dependency names; removing the annotation succeeds; restoring it fails again. |
| Foreign reliance | Cold and warm results retain foreign reliance. Removing the foreign fill makes its later call fail because the body is not yet implemented; restoring the fill restores foreign reliance. |
| Check-only behavior | The `main` containing an explicit stdout marker never runs. The marker is absent from all recorded stdout streams. |

These groups contribute **28 comparison pairs**. They force the tool's trace
threshold to zero to exercise reuse even for cheap declarations. Every group's
unchanged warm run checked zero entries, so the observations include skipped
user declarations, not merely a Base cache hit. There were 3,209 skipped entries
in those six warm runs, including Base; this count is instrumentation evidence,
not a performance result.

Another seven normal pairs cover the generated workload's cold/warm checks and
five timing pairs at the tool's ordinary `0.2 ms` trace threshold. One normal pair
primes the separate trust probe. In total: **36 normal pairs, zero mismatches**;
the separate tampered pair makes **74 subprocess observations** overall. No
subprocess timed out. The compiler checkout remained clean.

An earlier exploratory run used the wrong expected category for deleting a
foreign fill. Both implementations actually reported the same ordinary
before-definition error. The final runner expects that compiler-established
result; this was a fixture expectation correction, not an upstream discrepancy.

## Separate local-cache trust probe

The runner first checks this original claim normally to populate its own cache:

```bend
import Base

def alleged() -> {6n == 6n : Nat}:
  {==}
```

It then changes the equality's right side to `7n`, and deliberately replaces
only the stored `alleged#0f` trace's dependency list `d` with `[]`. This preserves
valid cache JSON and its compiler-version identity. The exact altered entry is
recorded in [cache-trust-entry.json](results/cache-trust-entry.json).

Ordinary Bend exits **1** with the expected/observed equality diagnostic.
The external cache exits **0**, prints `ALL PROOFS CHECK`, and reports all 533
entries skipped. The input mutation and both output streams are retained in the
artifacts. This is an intentional violation of the local trace store's trust
premise, **not** a mismatch in the normal invalidation corpus or evidence that
ordinary Bend accepts false equality.

Consequently, arbitrary valid cache JSON cannot be treated as compiler evidence.
Version hashes alone do not authenticate a trace's contents or establish that
it was produced by a successful check. A production design would need an
explicit trust/integrity model as well as invalidation correctness, and must not
present this tool's cached success as an uncached compiler or kernel judgment.

## Timing observations and limits

The decision written before the run was to consider a production prototype only
after exact normal equivalence and actual warm reuse were established. The
workload contains 32 original closed equality proofs over a recursive Nat
reconstruction function. Each timed sample includes Bun startup, source loading,
Base checking, compiler checking or trace validation, verdict output, and cache
I/O. Samples alternate the plain/cached order across five pairs. Warm-up and the
instrumented skip witness are separate from the five reported pairs; the witness
at the default threshold skipped 47 entries.

On this shared Linux ARM64 host (12 logical CPUs), the recorded plain median was
478.7 ms (439.3–824.8 ms) and cached median 436.9 ms (332.6–490.4 ms). The ranges
overlap substantially. Load averages were approximately `10.5, 15.3, 10.3` at
start and `10.6, 14.7, 10.3` at end. Per-child user/system CPU times are recorded,
but no isolated source-level limiter profile was taken. The workload is not a
representative large user project, and no IntelliJ capture-to-highlight path was
measured. The limiter and application impact are therefore unknown: **the result
is inconclusive and does not support a speedup or adoption claim**. Upstream M4
benchmark numbers from [PR #2045](https://github.com/bendlang/bend/pull/2045) are
not local evidence.

## Adoption decision and follow-up boundary

Keep this as research. The useful result is an executable differential harness
and concrete invalidation/trust witnesses. If a future profile shows checks
dominate editor latency, evaluate an opt-in advisory acceleration separately:

- Preserve the existing `AnalysisKey`, closed current-buffer snapshot, compiler
  identity, root-specific namespace/order and sibling-law rules. File text plus
  direct imports is not enough: the fill-load-order witness demonstrates why.
- Include erased/template and unsafe/foreign reliance facts, incomplete states,
  missing inputs and tooling changes in the cache contract. Never infer a proof
  verdict from source presence or a cache-hit count.
- Keep the in-memory compiler patch seam hash-pinned, bounded, cancellable and
  optional. A failed or untrusted cache must fall back to ordinary checking.
- Define concurrent writers, corruption, trusted storage and disposal before
  introducing persistence. Measure the real selected-root editor path on
  representative projects under controlled load before choosing a design.

These constraints follow architecture A3 and A5–A8 and specification #1's P5/P11
requirements. This experiment does not validate arbitrary tool versions,
unsaved IDE snapshot integration, concurrent cache writers, cancellation races,
kernel caching, all language forms, or all platform distributions.

## Evidence, provenance and self-review

- [summary.json](results/summary.json): exact identities, source hashes, machine,
  load, ordinary verdict, timing ranges and separate trust observation.
- [runs.json](results/runs.json): each command, exit, wall/child CPU times, source
  hashes, cache counts/hashes, exact-output hashes and comparison verdict.
- [outputs.jsonl](results/outputs.jsonl): complete stdout/stderr strings keyed by
  `output_record`; UTF-8 encoding reconstructs the exact bytes used by the hashes.
- [fixtures](fixtures): initial original books; `run.py` specifies transitions.

The runner, programs and this report are original work. Upstream code, tests,
patches, fixture bodies and documentation were not copied or adapted into the
repository. The external cache was executed from the clean upstream checkout;
it is not redistributed in the plugin, formatter or source artifacts here. No
new upstream notice payload is introduced.

Author self-review under `REVIEWER.md`: reviewed the new research-only files
against base `9e14236`, checked real outputs and code paths, and matched the
recommendation to A3/A5–A8 and specification #1's check-only, completeness,
reliance and snapshot requirements. No runtime owner or API changed. The normal
equivalence result and deliberate trust failure remain distinct. No actionable
finding in this research slice; this is self-review, not independent review.
Python syntax compilation and the real experiment ran; Gradle/plugin tests were
not run for research-only files.
