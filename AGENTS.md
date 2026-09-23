# Working agreement

## Required reading by role

These instructions apply to implementers and reviewers, including automated review agents.

- **Reviewing a PR, branch, commit or working-tree change:** read [REVIEWER.md](REVIEWER.md) before starting the review and follow its procedure. This is required even when the task only asks for a review and no implementation is planned. Read the architecture sections and issue criteria it calls for before assessing the diff.
- **Implementing a change:** follow the preparation below, then read and apply [REVIEWER.md](REVIEWER.md) before reporting the task complete or handing it off for review.
- **Doing both:** follow both requirements. Label your own review as self-review; do not present it as independent review.

REVIEWER.md is the review procedure incorporated by this agreement, not optional background reading. Review findings must cite the applicable issue criterion or architecture rule and concrete evidence as described there.

## Read before implementation

1. Read the assigned issue, its relevant blockers and the specification sections it relies on. The specification is [GitHub issue #1](https://github.com/dearlordylord/bend-idea/issues/1); historical SPEC.md links are obsolete.
2. Read [ARCHITECTURE.md](ARCHITECTURE.md), particularly the package graph, rule IDs A1–A9, issue ownership row and contracts introduced at this stage. Read [CONTEXT.md](CONTEXT.md) for domain terminology.
3. Inspect the existing owner implementation and tests before adding another abstraction. The repository currently has a file-recognition scaffold; most services in the architecture are future contracts, not existing code.

Explicit user instructions take precedence. Feature issues own acceptance behavior and delivery scope; ARCHITECTURE.md owns implementation boundaries. The user's Scala 3 decision supersedes older Kotlin recommendations in issues and reference material. Implement the plugin in Scala 3. Bend is the supported source language/external compiler and may appear in fixtures; do not implement plugin components in Bend.

## Working on a task

- Briefly identify the package owner, shared APIs/contracts affected, applicable architecture rules and intended verification before editing implementation. Keep this proportional to the change.
- Deliver the assigned slice. Introduce only the shared contracts it needs, leaving room for the later consumers listed in the architecture. Do not scaffold the whole roadmap.
- Follow the allowed dependency direction. Put language rules in their shared owner; feature handlers consume those rules. Do not duplicate resolution, loading, compiler decoding or freshness policy inside a feature.
- Keep policies functional and data explicit. Keep platform handles and I/O at the documented boundaries. Mutation needed by IntelliJ belongs to the appropriate lifecycle/write-command owner.
- Preserve root/source identity, snapshot provenance and honest result states when changing analysis. Use the actual compiler for compiler judgments; missing optional capabilities remain unavailable.
- Use IntelliJ editor fixtures for observable editor behavior and real pinned Bend subprocess tests for compiler integration. Add focused lower-level tests when they address a concrete parser, mapping, transition or boundary risk.
- Run relevant checks using a full JDK 21: `./gradlew check` covers editor fixtures and `architectureTest`; `buildPlugin verifyPlugin` covers packaging/compatibility. Extend the compiled dependency rules with new boundaries as described in ARCHITECTURE.md. Do not report planned, skipped or unavailable checks as passing.
- Preserve supplied reference checkouts and the protected trusted Bend core. Reference implementations inform the design; their architectures are not automatically applicable here.

## Before finishing

Use [REVIEWER.md](REVIEWER.md) to inspect the change and affected consumers. A reviewer file has effect here because this agreement explicitly requires it; merely adding it does not run a separate reviewer.

Report the behavior delivered, validation performed, applicable architectural changes and remaining gaps. In a PR, fill out the repository template with evidence rather than only checked boxes. Distinguish an implementer's self-review from independent review.

If a proposed change violates a rule, first look for a solution inside the current boundary. If a design change is necessary, describe the affected contract and tradeoff; update architecture, relevant guidance and checks together. Do not silently relax an enforcement rule or bury a deviation in a utility package. Routine conforming implementation choices require no extra permission.

## Maintaining guidance

Keep detailed architecture rules in ARCHITECTURE.md and terminology in CONTEXT.md. This file defines the workflow; REVIEWER.md defines the review procedure. Avoid copying detailed rules between all three files or into every issue. Add nested AGENTS.md files only for genuinely local constraints, and keep them consistent with the root agreement.
