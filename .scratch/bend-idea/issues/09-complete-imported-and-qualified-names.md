# 09: Complete imported and qualified names

**What to build:** Complete imported definitions and constructors using file-local aliases and current unsaved dependency contents.

**Blocked by:** [08: Configure libraries and complete Base names](08-configure-libraries-and-complete-base-names.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Relative, absolute and cached hash imports resolve with canonical identity; cycles and missing dependencies terminate gracefully.
- [ ] Only a direct import’s own declarations appear under its alias; aliases are not re-exported and constructors are at file scope.
- [ ] Base loaded transitively contributes empty-namespace names; local bindings and current-file names retain correct precedence.
- [ ] Dotted completion handles literal dotted names and alias prefixes, replaces trailing suffixes correctly and never suggests receiver fields.
- [ ] Fixtures cover unsaved imported files, diamonds, symlink/namespace conflicts, duplicate spellings, transitive Base and bounded invalidated caches.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
