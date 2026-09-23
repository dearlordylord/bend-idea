# Architecture and behavior review

Use this procedure for implementation self-review and for separately requested reviews. It does not automatically launch a reviewer or establish a CI gate.

## Establish the review scope

- Read the originating issue and relevant acceptance criteria, root AGENTS.md, and the applicable ARCHITECTURE.md sections/rules A1–A9.
- Identify the reviewed diff/base and changed package owners. Follow changed contracts to their existing consumers and tests; include the later consumers named in the architecture when assessing a new public API.
- Treat Scala 3 as selected. Historical Kotlin recommendations do not override that decision. Do not request another language evaluation as a routine review gate.

## Review in three passes

### 1. Behavior and integration

Does the slice meet the issue's observable acceptance criteria? Does it reuse the shared syntax, resolver and loader? Where relevant, trace an incomplete or unsaved source edit through the actual editor action. Verify that missing Base, indexes or compiler capabilities produce the documented fallback. Check for regressions to existing consumers of changed APIs.

### 2. Architecture and future consumers

Apply only the rules relevant to this diff:

- A1–A2: implementation language, ownership, dependency direction, slice boundaries and extension instantiation.
- A3–A4: single semantic owner, distinct identities, immutable policy values and explicit effects.
- A5–A7: root/dependency revisions, publication races, isolated diagnostics, process lifecycle, completeness/reliance and genuine compiler capabilities.
- A8–A9: file-local indexes, current buffers, invalidation, reference/binding preservation, conservative transformations and undo.

For a new contract, identify at least one later consumer it must support. For example: can the completion identity support rename, can the first check result support multiple proof roots, and can today's source mapping support structured diagnostics? Assess the contract shape; do not require implementing those future features now.

Consult ARCHITECTURE.md for the exact rules rather than inventing additional requirements. Prefer a local correction over a new framework or a speculative generalization.

### 3. Evidence

Inspect the relevant test coverage and actual check results. An import/dependency test cannot establish language semantics; a mocked process cannot establish that real checking never executes main. Check that cancellation races and disposal are exercised when the change introduces those behaviors. Check that architecture tests include the production classes they claim to govern.

Do not claim commands were run unless they were. State a missing required gate or untested material risk explicitly. Avoid demanding unrelated full-suite runs after adequate checks have passed.

## Report findings

For each actionable finding, give the location, violated acceptance criterion or architecture rule, a concrete trigger/consequence, and the smallest plausible correction. Distinguish correctness/contract violations from optional design suggestions. Do not block work for personal style preferences absent a documented standard.

If there are no findings, say so and report the reviewed scope, evidence and remaining limitations. If this is implementer self-review, label it as such. An intentional architectural exception must have a recorded rationale, narrow scope and corresponding documentation/check changes; it must not appear as an unexplained suppression.
