# 26: Show optional parameter-name hints

**What to build:** Enable unobtrusive parameter-name hints for calls with reliably resolved source signatures.

**Blocked by:** [24: Show parameter information](24-show-parameter-information.md).

**Status:** ready-for-agent

**Priority group:** 3 — Additional native editing.

- [ ] Hints are optional and use source signatures for supported function calls and constructor fields.
- [ ] Parameter alignment respects erased/template arguments and any supported sugar; ambiguous cases omit hints.
- [ ] Do not label the hints as inferred types or repeat redundant obvious labels unnecessarily.
- [ ] Fixtures verify hint labels/positions, enablement, nested calls and stale-signature invalidation.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [IntelliJ language feature guide](../../../.references/idea/08-custom-language-overview.md)
- [Installed-source reference Base](../../../.references/bend/bend2/base.bend)
