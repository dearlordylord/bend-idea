# 28: Navigate between laws, proofs and holes

**What to build:** Follow law/proof gutter links and move between named or TODO holes in the selected source context.

**Blocked by:** [18: Find and highlight usages](18-find-and-highlight-usages.md), [27: Select and check proof roots](27-select-and-check-proof-roots.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Navigate from a law to available candidate fills and back; multiple implementations offer a choice with root context.
- [ ] Named and TODO holes are discoverable and navigable without implying their goals are available from the CLI.
- [ ] Unfilled laws, missing candidates and stale root results remain explicit; a matching def is not proof success.
- [ ] Fixtures cover same-file/cross-file fills, multiple roots, hole edits and accurate gutter/next-hole navigation.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Concrete laws and proof example](../../../.references/bend/demos/app_pong_game_2d)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Quint editor actions](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/editor)
