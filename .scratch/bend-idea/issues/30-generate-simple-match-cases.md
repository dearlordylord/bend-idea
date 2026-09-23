# 30: Generate simple match cases

**What to build:** Insert a case-analysis skeleton for a known datatype parameter or pattern field.

**Blocked by:** [24: Show parameter information](24-show-parameter-information.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Offer the action only when the scrutinee is a supported matchable binder with a statically known datatype; reject computed or ordinary let-bound values.
- [ ] Generate constructor patterns with fresh field names and appropriate source aliases without claiming exhaustive dependent refinement.
- [ ] Preserve binder order and existing cases; empty types and ambiguous dependent types receive conservative handling.
- [ ] Bodies are explicit TODOs; fixture/compiler cases demonstrate valid skeleton shapes and unavailable actions where constraints cannot be established.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Bend language guide](../../../.references/bend/guide/GUIDE.md)
- [Installed-source reference Base](../../../.references/bend/bend2/base.bend)
