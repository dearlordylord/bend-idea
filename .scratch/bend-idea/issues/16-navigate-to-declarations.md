# 16: Navigate to declarations

**What to build:** Follow a name to its local, imported or Base declaration using standard IntelliJ navigation.

**Blocked by:** [09: Complete imported and qualified names](09-complete-imported-and-qualified-names.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] References use the shared completion resolver and distinguish lexical bindings, literal dotted names, alias prefixes and member ranges.
- [ ] Constructor/type same-spelling references select the appropriate category; source identities remain stable across aliases.
- [ ] Navigation works with unsaved imported buffers and configured library sources; unresolved references fail gracefully.
- [ ] Invalid forward targets, if navigable, remain distinguishable from valid-in-scope candidates; navigation does not certify semantic validity.
- [ ] Fixtures invoke navigation from declarations/usages and cover shadowing, aliases, nested binders, Base and incomplete input.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [IntelliJ references and navigation guide](../../../.references/idea/13-references-and-navigation.md)
