# 31: Browse proof progress

**What to build:** Inspect laws, candidate implementations and unfinished holes in a proof-oriented tool window.

**Blocked by:** [28: Navigate between laws, proofs and holes](28-navigate-between-laws-proofs-and-holes.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Display source inventory with navigation, filtering and the selected root’s latest check status.
- [ ] Distinguish source presence, incomplete work, compiler success, unsafe/foreign reliance and stale/unavailable results.
- [ ] Respond to unsaved edits/root changes without promoting a candidate implementation into a verified proof.
- [ ] Fixtures verify panel data/navigation and update/disposal behavior for multiple roots and canceled checks.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Concrete laws and proof example](../../../.references/bend/demos/app_pong_game_2d)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [IntelliJ tool windows guide](../../../.references/idea/21-tool-windows.md)
