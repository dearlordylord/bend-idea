# Manual smoke checklist: #41–47 and #62–66

Use a local build containing these changes. For #41–47, configure the pinned
Bend 2.0.25 structured helper as described in the [README](../README.md#compiler-checking).
The selection checks (#62–66) do not need a compiler. In IDEA, use **Find Action**
to locate **Expand Selection** or the named Bend actions below.

## Compiler-backed assistance

- [ ] **#41 — Structured diagnostics:** Introduce a Bend type error, check the file, and confirm the first error highlights its source range.
- [ ] **#42 — Goal and context:** Put the caret on the first `?name` hole and run **Inspect Bend Goal**. Confirm its expected type and local names appear.
- [ ] **#43 — Expression types:** Check a valid file, then open **Quick Documentation** on an expression. Confirm it labels a compiler expression type.
- [ ] **#44 — Expected-type completion:** Inspect a typed hole, then invoke completion there. Confirm a matching local name ranks near the top.
- [ ] **#45 — Resource availability:** Run **Explain Bend Resources** at that hole. Confirm binder quantities and candidate results appear.
- [ ] **#46 — Proof edit:** Run **Try Bend Reflexivity** at a simple equality hole in a definition. Accept the preview, then Undo once.
- [ ] **#47 — Normalization:** Run **Normalize Bend Expression** on a closed expression in a checked file. Confirm it displays a result without changing source.

## Expand Selection

- [ ] **#62 — Operators and types:** Expand Selection inside an operator or type expression; confirm the selection grows by syntax.
- [ ] **#63 — Calls and applications:** Expand Selection inside a call or chained application; confirm it grows through the call.
- [ ] **#64 — Collections and indexed writes:** Expand Selection inside a tuple, list, array, or indexed write; confirm it selects a part before the whole form.
- [ ] **#65 — Bodies and statements:** Expand Selection inside a nested statement, then again to include its enclosing body.
- [ ] **#66 — Atoms and qualified names:** Expand Selection inside a quoted literal or qualified name; confirm it selects the complete atom or name part.
