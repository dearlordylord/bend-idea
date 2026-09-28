# Multiple Bend diagnostics from one check (issue #75)

Researched 2026-09-27 against the official [Bend v2.0.32 tag](https://github.com/bendlang/bend/releases/tag/v2.0.32), which resolves to commit [`573002f01ec6c52416d44489543f69a9625facf8`](https://github.com/bendlang/bend/commit/573002f01ec6c52416d44489543f69a9625facf8). The installed `bend version` reports `bend 2.0.32`. The runtime observations below used that binary; the source claims link to the immutable upstream commit.

## What the current check does

- `--check-only` enters [`cli_file`](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/main.ts#L228-L321). It awaits one `book_read`, and only after that succeeds calls `cli_verdict`. Its single `catch` formats one thrown error and exits with status 1 ([lines 291–320](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/main.ts#L291-L320)). `book_read` loads sources, validates the book, and wraps the first thrown failure in `Check_Fail` ([lines 797–824](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/main.ts#L797-L824)). The CLI error renderer accepts one `Err`, not a collection ([lines 858–868](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/main.ts#L858-L868)).
- [`book_load`](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L952-L1021) recursively loads and parses imports before parsing their importer. Thus an error while parsing imported `Maybe.bend` prevents Bend from parsing root `main.bend`. The duplicate is thrown by [`parse_fresh`](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L2419-L2425) as a [`parse_fail`](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L1519-L1522). Even though the imported type's qualified key is `Maybe.Maybe`, `parse_fresh` also rejects its bare name `Maybe` because Base already declares that name. The type branch calls `parse_fresh` **before** registering the ADT and its constructors ([lines 2509–2536](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L2509-L2536)).
- Once parsing succeeds, [`book_valid`](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L3747-L3852) explicitly says it "throws the first Err" and checks declarations in order while mutating the book. It does not catch per-declaration failures or return a diagnostic list. The missing-case judgment comes from [`term_check`'s `Efq` branch](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L3583-L3602), which needs a resolved ADT and throws when constructors remain uncovered.
- The current structured `Err` holds an optional `Span`, but `Span.file` is a [`File` containing source text, namespace, and aliases, with no filesystem path](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L335-L355). The [text renderer](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L1467-L1495) prints a nearby source excerpt and declaration name, not a path. An IDE-ready multi-diagnostic protocol therefore also needs an unambiguous loaded-source identity, or a documented mapping to one, especially when different imports contain identical text.

## Two distinct limits shown by real checks

1. **Reported imported duplicate:** The exact two-file source is in [issue #75](https://github.com/dearlordylord/bend-idea/issues/75). `bend main.bend --check-only` exits 1 with only `duplicate declaration: Maybe` at the imported type. After renaming the type and both constructors to `Choice`, `Left`, and `Right` in a temporary copy, it exits 1 with `expected: cases for Maybe.Right` at `match value:`. The `Maybe.Right` prefix is the module namespace derived from `Maybe.bend`; the type name is `Choice`. **Inference:** the missing-case result on the original, invalid import would require parser recovery that provisionally retains rejected declarations. It is not an independently reached compiler judgment today.
2. **Independent errors after a valid parse:** This single-file input parses all declarations:

   ```bend
   import Base

   type Choice is Data:
     Left{}
     Right{}

   def bad() -> Nat:
     True{}

   def inspect(value: Choice) -> Nat:
     match value:
       case Left{}:
         0n
   ```

   `bend <file>.bend --check-only` exits 1 with only `expected: Nat; observed: Bool` at `bad`. Replacing `True{}` with `0n` makes it report `expected: cases for Right` at `match value:`. This demonstrates that even when parsing is complete and both declarations exist, validation stops at the earlier error. The checker’s declaration-order loop and first-error contract explain the observation.

## Fix boundary

The smallest useful **upstream** increment is an opt-in check result that collects multiple validation errors from *independent, successfully parsed* declarations and reports their source spans in a machine-readable list. `book_valid` is the point to investigate, but merely wrapping each loop iteration in `try/catch` is unsafe: it mutates `book.tlds`, fills checked bodies, and may instantiate templates while checking a declaration ([lines 3774–3851](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L3774-L3851), [template instantiation lines 3711–3745](https://github.com/bendlang/bend/blob/573002f01ec6c52416d44489543f69a9625facf8/bend2/bend.ts#L3711-L3745)). Recovery needs a defined valid state after a failed declaration, dependency suppression or explicit provisional results, deterministic ordering, and a final failing verdict. A separate optional CLI mode or compiler API can expose the array while the existing `--check-only` contract stays stable. This would cover the independent-error example first.

The **reported two-file case** additionally needs parser/loader recovery. An import parse error currently aborts before root parsing and before the rejected ADT/constructors enter the book. To diagnose that root match, upstream would need to continue loading with provisional symbols and label or suppress judgments that depend on invalid declarations. It cannot guarantee that the missing-case error is authoritative for the original source merely by accumulating thrown errors. A plugin-side retry that renames or deletes the collision checks a different program; a plugin source heuristic would not be a Bend compiler judgment. Under this repository's [A5–A7 contracts](../ARCHITECTURE.md), the IDEA plugin should consume and map each compiler-supplied diagnostic from the same immutable root snapshot, retain the first-error fallback, and avoid presenting a guessed second error as red compiler output.

### Suggested upstream acceptance probes

- Independent valid-parse declarations: report both `Nat`/`Bool` mismatch and missing `Right` case from the same unmodified file, with correct spans, one failed verdict, and no spurious dependent errors.
- Imported parser failure: define whether the compiler can report the duplicate plus a *conditional* missing-case finding on the unmodified two-file root. If it cannot safely recover the imported type, explicitly return only the duplicate. Either result should have a documented structured protocol and clear source identity for IDE mapping.
