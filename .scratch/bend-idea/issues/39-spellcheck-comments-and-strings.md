# 39: Spellcheck comments and strings

**What to build:** Use IDE spelling assistance for prose in Bend comments and supported literal text.

**Blocked by:** [02: Highlight and comment Bend code](02-highlight-and-comment-bend-code.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Spellcheck appropriate comment/string text while excluding language identifiers, operators and escape syntax.
- [ ] Handle multiline literals and escaped characters without corrupting source ranges.
- [ ] Use standard IDE spelling settings and fixes, preserving valid literal spelling when edits apply.
- [ ] Fixtures verify inspected text/ranges and representative corrections around escapes and comments.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [IntelliJ language feature guide](../../../.references/idea/08-custom-language-overview.md)
- [VS Code grammar and lexical behavior](../../../.references/bend-vscode/syntaxes/bend.tmLanguage.json)
