# Optional managed lint integration validation

Scope: issue #89 integration slice against `9e14236`, bend-lint npm 0.3.0
(source c6f31c0c2ef4587368dd6a5443a6d42e22e975fc), Bun 1.4.4+38afee6c8.
The configured compiler remains authoritative. Custom project rules and source fixes
remain outside this slice. Background lint is an explicit opt-in, disabled by default.

Implementer self-review followed REVIEWER.md: A1–A4 retain Scala/shared graph and
adapter ownership; A5/A7 retain root provenance, separate compiler/lint outcomes,
skipped coverage and installed-input freshness; A6 bounds process/downloads and
keeps installation explicit. Existing mappings support native annotations and
future rules without another source loader. Independent adoption review identified
and the implementation corrected manifest observation ordering, added-artifact
freshness, benign upstream program timestamp refreshes and hidden skipped status.

Real integration initially failed strict readiness inventory: Bun added
`cache/bun/@t@/a547cbd3225f2943.pile` and
`cache/bun/@t@/1eed8e6ce8a5e748.pile` after preparation. Preparation and analysis now
disable the runtime transpiler cache via documented
`BUN_RUNTIME_TRANSPILER_CACHE_PATH=0`; executable/source inventory remains strict.
A subsequent real run exposed absolute config entries: upstream prefixes the
configuration folder to every entry and emitted a non-JSON `ReferenceError:
io_code is not defined` for the resulting nonexistent path. The backend now
passes a snapshot-relative forward-slash entry as required by the current CLI.
A direct pinned runtime probe confirmed relative entries return valid JSON without
executing the IO main. Neither correction relaxes protocol or compiler assertions.

The broad retry exercised 97 tests: 94 passed, three failed at the absolute-entry
issue above, with zero skipped/errors. Passing suites included installer (4),
freshness races (2), protocol (4), process (3), settings (1), presentation (1),
background (14), and selected proof roots (10). The final narrow retry validates
the three corrected real failures and all focused lint boundaries.

Final focused Gradle run passed in 1m 1s on full JDK 21: **19 tests and
2 architecture tests, zero failures/errors/skips**. It ran `spotlessApply`, the
lint/installer/protocol/process/settings/presentation/freshness suites, the two
real graph scenarios, and the native lint editor action. Real configured compiler
inputs were Bend 2.0.37 at `4ff8545f19b09b7d2b8ca2303668100eac8efd66` with
Bun 1.4.2+744846f84; lint used its separately pinned Bun 1.4.4/internal Bend 2.0.39.
The unsaved CRLF/astral root and dependency warnings mapped to exact original
ranges; an IO main did not execute. Aging prepared JS beyond the upstream
24-hour touch threshold stayed current; adding an unrecorded artifact and
mutating input during compiler probing were rejected. Native annotation cleared
after an edit. Two actual installations, cancellation, replacement/rollback,
checksum and native artifact rejection ran without skipping.

A single warm Linux ARM64 observation over 138 installed inputs measured
3,141.99 ms for canonical checking plus lint and 5.75 ms for the first freshness
check after the linter touched aged JS. This is one diagnostic observation, not
a benchmark or speed claim; it includes hashing the changed program timestamps'
content identity. Normal unchanged metadata uses a fast path.

Final implementer self-review found no remaining issue #89 slice/A1–A8 violation
in the changed contracts and existing consumers. Independent adoption review
remains separately reported by the parent integration task. Full merged
project and packaging gates belong to the integration branch. Linux ARM64 is the
exercised runtime host; shipped archive pins alone do not prove other host behavior.
