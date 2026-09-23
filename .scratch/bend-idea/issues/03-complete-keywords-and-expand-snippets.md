# 03: Complete keywords and expand snippets

**What to build:** Request keyword suggestions and expand the six existing VS Code snippets into editable Bend forms.

**Blocked by:** [02: Highlight and comment Bend code](02-highlight-and-comment-bend-code.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Explicit completion offers context-appropriate keywords without introducing syntax from Bend 1 or Python.
- [ ] Function, datatype, law, match, do and import snippets expand with editable placeholders and a sensible final caret position.
- [ ] Suggestions are suppressed in comments and strings, including incomplete literals.
- [ ] Fixture tests invoke completion and apply snippets through editor APIs, including incomplete input and ordinary typing.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
- [VS Code behavior and compiler tests](../../../.references/bend-vscode/test)
- [IntelliJ completion guide](../../../.references/idea/14-code-completion.md)
