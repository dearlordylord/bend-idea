# 10: Complete module import paths

**What to build:** Browse available Bend module paths from an import statement.

**Blocked by:** [09: Complete imported and qualified names](09-complete-imported-and-qualified-names.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Suggest Base, relative directories, absolute paths, Bend filenames and locally cached hash package paths.
- [ ] Accepting directories continues path completion; completing filenames replaces the complete path fragment correctly.
- [ ] Include applicable newly created/open source files; partially typed or unreadable directories do not disable completion.
- [ ] Completion performs no package download and avoids unbounded directory/cache traversal.
- [ ] Fixtures exercise partial paths, missing directories, cached hashes and replacement with text after the caret; unsupported whitespace-containing module syntax is not falsely offered as valid.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
- [VS Code behavior and compiler tests](../../../.references/bend-vscode/test)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
