# Issue tracker

The tracker is [dearlordylord/bend-idea on GitHub](https://github.com/dearlordylord/bend-idea/issues). [Issue #1](https://github.com/dearlordylord/bend-idea/issues/1) is the approved specification; feature issues own acceptance scope. Historical SPEC.md references are obsolete. Root AGENTS.md and REVIEWER.md define implementation and review workflow; ARCHITECTURE.md owns package boundaries. Scala 3 is the selected plugin implementation language.

Read a referenced issue with `gh issue view NUMBER --repo dearlordylord/bend-idea --json body,comments,state,url`. Inspect blockers before starting implementation. Use current issue state rather than inferring completion from release notes. Completion comments must identify acceptance evidence and remaining gaps; an upstream capability proposal is distinct from adoption.
