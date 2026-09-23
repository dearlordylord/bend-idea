# 43: Complete against an expected type

**What to build:** Rank available names using the compiler-provided expected type at a supported completion location.

**Blocked by:** [09: Complete imported and qualified names](09-complete-imported-and-qualified-names.md), [41: Inspect compiler-provided goals and context](41-inspect-compiler-provided-goals-and-context.md), [42: Inspect actual expression types](42-inspect-actual-expression-types.md).

**Status:** ready-for-agent

**Priority group:** 5 — Compiler-backed semantic assistance.

- [ ] Keep lexical/import scope as the candidate boundary and use compatible goal/type information to rank or conservatively filter candidates.
- [ ] Bound dependent instantiation/comparison work and cancel outdated requests without delaying ordinary completion.
- [ ] Fallback to established source completion when expected types are unavailable; do not insert guessed proof terms or omit explicit arguments automatically.
- [ ] Editor/adapter tests show improved ranking on known cases plus predictable fallback, stale-result rejection and timeout behavior.

**Capability or scope constraint:** External prerequisite: compiler support for the comparisons/instantiations this feature actually performs; do not implement a competing dependent checker in Kotlin.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [IntelliJ completion guide](../../../.references/idea/14-code-completion.md)
