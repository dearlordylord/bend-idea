# Upstream follow-up — 2026-10-15

Read-only upstream refresh after Bend IDEA 0.1.22. This supplements the [October 14 assessment](upstream-update-assessment-2026-10-14.md) and [bend-lint investigation](bend-lint-upstream-2026-10-14.md); historical recommendations in those notes describe their inspection time, while the implementation follow-up records delivery.

## Current upstream state

| Project | Verified current state | Change since October 14 |
| --- | --- | --- |
| bend-lint | Default-branch HEAD [`02ef84b38b8c06cbd607e270c4930e633e9dab0f`](https://github.com/MattCozendey/bend-lint/commit/02ef84b38b8c06cbd607e270c4930e633e9dab0f). The GitHub releases and tags endpoints return empty lists. Its [manifest](https://github.com/MattCozendey/bend-lint/blob/02ef84b38b8c06cbd607e270c4930e633e9dab0f/package.json) remains `0.1.0`, private, with Bun 1.4.3. | None. This is a source revision, not a published stable release. |
| bend2-lsp-rs | Default-branch HEAD [`217a2e1c6d7830a1e394173a51ba689fadbbafb6`](https://github.com/IlyaGulya/bend2-lsp-rs/commit/217a2e1c6d7830a1e394173a51ba689fadbbafb6). Latest published stable release remains [v0.6.1](https://github.com/IlyaGulya/bend2-lsp-rs/releases/tag/v0.6.1), published October 13, 15:56:53 UTC. [Main is two commits ahead of v0.6.1](https://github.com/IlyaGulya/bend2-lsp-rs/compare/v0.6.1...217a2e1c6d7830a1e394173a51ba689fadbbafb6). | None. Portable compiler diagnostics and query allocation changes remain unreleased main work. |

Primary API observations: bend-lint [HEAD](https://api.github.com/repos/MattCozendey/bend-lint/commits?per_page=1), [releases](https://api.github.com/repos/MattCozendey/bend-lint/releases), [tags](https://api.github.com/repos/MattCozendey/bend-lint/tags); bend2-lsp-rs [HEAD](https://api.github.com/repos/IlyaGulya/bend2-lsp-rs/commits?per_page=1), [releases](https://api.github.com/repos/IlyaGulya/bend2-lsp-rs/releases). Queried live with Python's HTTPS client on October 15; the browser tool could not open the API URLs.

## Research versus delivered behavior

The projects remain comparison sources, not bundled plugin engines or adopted dependencies. Selected ideas have moved beyond research into the [0.1.22 changelog](../CHANGELOG.md): import-chain diagnostics with original-cause navigation, negotiated compiler source identity, three optional native inspections, and request-local Find Usages batching. The [implementation and validation record](upstream-update-assessment-2026-10-14.md#authorized-implementation-follow-up) distinguishes independent Astra reviews, self-review, tests and limits. Tight law ranges were verified against the real compiler rather than changed with a trimming heuristic.

The inspection subset is deliberately narrower than upstream: unused-alias information does not delete imports; canonical path fixes revalidate the loading graph and support native undo; resource/performance advice is not inferred from source occurrence counts. No LSP/lint engine, private compiler recovery patch, compiler upgrade or upstream expressive source was imported.

## Further adoption recommendation

No newly published source or release warrants another adoption pass today. The October 14 assessment's final decision still applies:

- Multiple independent compiler errors need the safe compiler capability tracked by [issue #75](https://github.com/dearlordylord/bend-idea/issues/75). Copying private parser/checker patches would add compatibility and completeness obligations; an available upstream patch is not an official supported compiler contract.
- Resource and performance inspections need trustworthy source-correlated observations and representative benchmarks before production advice or transformations. Keep the [resource-query investigation](issue-73-compiler-resource-query.md) as the evidence boundary.
- Completion, hint preparation and allocation ideas remain profiling candidates. The [Find Usages profile](usages-batching-2026-10-14/README.md) supports the shipped request-local batching only; it does not establish that another cache or Rust storage layout would improve Scala/IntelliJ workloads.
- Import removal and broader automatic rewrites require their own loading, binding, compiler and undo evidence. Upstream rule applicability labels do not establish those preconditions.

This does not mean the application has no worthwhile backlog: local issue triage should select the next independently deliverable feature or correctness slice. It means these unchanged upstream revisions supply no additional ready-to-adopt production change beyond the completed assessment.

## Validation and self-review

Read `REVIEWER.md`, relevant architecture ownership/authority sections and the existing research/delivery record. Author self-review: recommendations preserve native syntax/resolution/loading ownership, honest compiler capabilities and conservative transformations (A1–A9); no runtime contract changes or new license notices arise from this original linked analysis. Only live metadata and existing local evidence were inspected today. No Gradle, upstream runtime tests or benchmarks were run for this documentation-only refresh. This is not an independent code review.

## Open issue triage

Live open-issue queries returned four tasks; issue bodies and comments were read.

1. [#88: inline-body formatter alignment](https://github.com/dearlordylord/bend-idea/issues/88) is the highest-priority concrete correctness slice. Header comma spacing can move an inline statement while leaving its continuation at the previous physical column. Reproduce against the supported real compiler, preserve the layout anchor or reject the edit conservatively, and verify both the shared formatter CLI and native Reformat Code. The report used a different compiler revision, so confirmation against current supported inputs is part of pickup, not already established by this triage.
2. [#89: managed bend-lint integration](https://github.com/dearlordylord/bend-idea/issues/89) remains an unmerged prototype/productization handoff, not delivery in 0.1.22. Its old TypeScript-engine and absent-license evidence predates the rewritten Bend engine/current MIT license. Refresh the pinned compatibility, installation and distribution design before implementing atomic installation, cancellation, offline errors and rollback. Preserve the separate branch; do not treat the old prototype's test results as evidence for today's engine.
3. [#74: native versioned check protocol](https://github.com/dearlordylord/bend-idea/issues/74) is partly implemented by the plugin's protocol/helper. The remaining upstream-native contract is distinct from upgrading the bundled helper. Its historical issue text cites 2.0.25, while the current [support matrix](../docs/compiler/support.md) records the tested 2.0.35 pairing; use the latter for current support facts.
4. [#75: multiple independent compiler errors](https://github.com/dearlordylord/bend-idea/issues/75) remains explicitly blocked on trustworthy recovery. Import-cause projections shipped in 0.1.22 are extra presentations of genuine errors, not new independent compiler judgments.

Recommended order: confirm/fix #88 first; refresh #89's design next; keep #74/#75 as compiler-contract work. No issue was edited or closed, and no implementation was started by this research refresh.
