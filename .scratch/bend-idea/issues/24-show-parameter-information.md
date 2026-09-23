# 24: Show parameter information

**What to build:** See the active function parameter, constructor field or datatype argument while entering an application.

**Blocked by:** [17: Show Quick Documentation](17-show-quick-documentation.md).

**Status:** ready-for-agent

**Priority group:** 3 — Additional native editing.

- [ ] Parameter information resolves ordinary calls, constructor braces and datatype arguments separately.
- [ ] Show erased and template parameters and account for omitted leading datatype quantity blocks; use a law signature for its fill.
- [ ] Nested applications and incomplete lists identify the active argument correctly without forcing complete dependent inference.
- [ ] Fixtures cover partial applications, nested generics, law-backed functions, constructors, templates and unsaved signatures.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Installed-source reference Base](../../../.references/bend/bend2/base.bend)
- [IntelliJ language feature guide](../../../.references/idea/08-custom-language-overview.md)
