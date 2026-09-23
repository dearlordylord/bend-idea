# 25: Improve semantic reading and selection

**What to build:** Read resolved names with semantic colors and navigate syntax using breadcrumbs and selection expansion.

**Blocked by:** [15: Fold code and browse file structure](15-fold-code-and-browse-file-structure.md), [16: Navigate to declarations](16-navigate-to-declarations.md).

**Status:** ready-for-agent

**Priority group:** 3 — Additional native editing.

- [ ] Semantic colors distinguish supported locals, parameters, constructors, datatypes, laws, definitions and aliases through actual resolution.
- [ ] Breadcrumbs follow the surface declaration/body nesting; expanding/shrinking selection follows meaningful syntax ranges.
- [ ] Unresolved or malformed code falls back to lexical highlighting and sensible selection rather than blocking editing.
- [ ] Fixtures verify representative color categories, breadcrumb targets and selection ranges around nested proof and application forms.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint editor actions](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/editor)
- [IntelliJ language feature guide](../../../.references/idea/08-custom-language-overview.md)
- [IntelliJ references and navigation guide](../../../.references/idea/13-references-and-navigation.md)
