# 02: Highlight and comment Bend code

**What to build:** Read Bend code with theme-aware syntax colors and toggle hash line comments using standard editor actions.

**Blocked by:** [01: Recognize Bend files](01-recognize-bend-files.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Declarations, names, literals, quantities, templates, holes, rewrites and operators receive appropriate configurable theme colors.
- [ ] Comment and literal contents are not interpreted as code; multiline literals and escapes retain correct highlighting across incremental edits.
- [ ] The lexer accounts for every character, reports invalid input, and makes progress on unfinished text; identifiers and UTF-16 ranges match Bend.
- [ ] The standard line-comment action toggles hash comments, including at end of file without a newline.
- [ ] Editor fixtures and lexer restart cases cover CRLF, astral characters in literals, unfinished strings and edits within existing tokens.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [VS Code grammar and lexical behavior](../../../.references/bend-vscode/syntaxes/bend.tmLanguage.json)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [IntelliJ lexer guide](../../../.references/idea/10-lexer.md)
