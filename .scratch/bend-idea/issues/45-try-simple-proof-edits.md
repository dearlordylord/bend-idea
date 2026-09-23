# 45: Try simple proof edits

**What to build:** Offer narrowly scoped proof edits such as reflexivity and validate the proposed change with the compiler.

**Blocked by:** [29: Generate a law implementation skeleton](29-generate-a-law-implementation-skeleton.md), [41: Inspect compiler-provided goals and context](41-inspect-compiler-provided-goals-and-context.md).

**Status:** ready-for-agent

**Priority group:** 5 — Compiler-backed semantic assistance.

- [ ] Offer only supported simple edits using current goal context, with a preview or deliberate application and undo.
- [ ] Recheck a snapshot containing the candidate edit before presenting it as validated; check the complete selected root before showing overall success.
- [ ] Never weaken a law, add unsafe annotations or hide remaining holes; distinguish a valid local step from a complete proof.
- [ ] Fixtures and real checks cover successful/rejected reflexivity, stale goals, remaining TODOs and undo without changing the original on failed preview.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Concrete laws and proof example](../../../.references/bend/demos/app_pong_game_2d)
- [Bend language guide](../../../.references/bend/guide/GUIDE.md)
