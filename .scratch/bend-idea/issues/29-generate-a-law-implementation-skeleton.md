# 29: Generate a law implementation skeleton

**What to build:** Create an editable law-filling definition with appropriate parameter names and an unfinished proof body.

**Blocked by:** [28: Navigate between laws, proofs and holes](28-navigate-between-laws-proofs-and-holes.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Generate bare fill parameters from the law telescope, including leading template clauses where applicable and stopping before existential witnesses.
- [ ] Use the selected target file’s alias and declaration context, avoid collisions and offer a deliberate target when multiple roots are plausible.
- [ ] Insert a TODO body and retain incomplete status; never weaken the law or add unsafe annotations.
- [ ] Fixture tests apply and undo generation, check source shape, and use the compiler to confirm the result is intentionally incomplete rather than incorrectly certified.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Concrete laws and proof example](../../../.references/bend/demos/app_pong_game_2d)
- [Bend language guide](../../../.references/bend/guide/GUIDE.md)
