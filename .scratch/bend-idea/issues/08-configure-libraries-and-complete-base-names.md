# 08: Configure libraries and complete Base names

**What to build:** Select a Bend installation and obtain completion from its actual Base source.

**Blocked by:** [07: Complete laws and proof bindings](07-complete-laws-and-proof-bindings.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Settings allow executable, Base source and package-cache selection with documented defaults, home expansion and diagnostics enablement.
- [ ] Base names and comments are read from source, remain distinct from language keywords, and are invalidated when the source/configuration changes.
- [ ] Completion after a dotted Base prefix replaces only the intended suffix, including existing text after the caret.
- [ ] Missing Base leaves current-file completion usable and surfaces an actionable setup state; a checker/library mismatch cannot silently present matching-toolchain results.
- [ ] Fixtures cover alternate installations, missing Base, source updates and signature changes without a hard-coded declaration catalog.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Installed-source reference Base](../../../.references/bend/bend2/base.bend)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
- [VS Code settings and feature baseline](../../../.references/bend-vscode/package.json)
