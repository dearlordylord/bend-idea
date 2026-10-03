# Bend formatter: EditorConfig and commit integration

Researched 2026-10-03 against official documentation and the current repository. Recommendations below were researched and inspected. The parent implementation task also ran the smoke checks recorded below.

## Recommended setup

EditorConfig defines shared style properties consumed by editors and tools. It does not define a command runner; invoking a formatter belongs to an editor integration, hook manager or CI. Properties are inherited from parent directories until `root = true`. [EditorConfig](https://editorconfig.org/)

The current Bend formatter already reads local EditorConfig, shares indentation decoding with the IDE and recognizes its own `bend_max_line_length` extension. It conservatively preserves line endings and final-newline presence; adding generic newline properties does not make this CLI enforce them. See [README](../README.md#editorconfig-and-wrapping).

Commit this configuration in the consuming project:

```ini
root = true

[*.bend]
indent_style = space
indent_size = 2
tab_width = 4
bend_max_line_length = 100
```

For a project without an existing hook manager, install the formatter and pre-commit, then commit `.pre-commit-config.yaml`:

```sh
brew install dearlordylord/tap/bend-format pre-commit
```

```yaml
minimum_pre_commit_version: '4.4.0'
repos:
  - repo: local
    hooks:
      - id: bend-format
        name: Format Bend
        entry: bend-format fix
        language: unsupported
        files: '\.bend$'
        require_serial: true
```

```sh
pre-commit install
pre-commit run --all-files
```

Local hooks accept explicit filenames automatically. `unsupported` runs an already installed executable; it does not install Bend. Since pre-commit 4.4.0, it is the documented replacement for `system`, which remains a temporary alias. Serial execution reduces repeated runtime starts. At commit time pre-commit temporarily hides tracked unstaged changes. Modified files fail the hook: inspect them, stage the intended changes and retry. An auto-fix conflicting with the saved unstaged patch is rolled back. For a read-only gate, change the entry to `bend-format check`. CI can run `pre-commit run --all-files`. [pre-commit documentation](https://pre-commit.com/), [staged-file implementation](https://github.com/pre-commit/pre-commit/blob/main/pre_commit/staged_files_only.py)

Keep configuration tracked and committed. Untracked or ignored `.editorconfig` files can remain visible during pre-commit's staged-content run. Files outside the repository also remain visible to normal parent-directory lookup; the root configuration stops that lookup. The staged-file implementation hides tracked differences rather than constructing an isolated checkout. [Implementation](https://github.com/pre-commit/pre-commit/blob/main/pre_commit/staged_files_only.py)

## What established formatters do

Prettier documents several hook integrations rather than putting executable commands in EditorConfig. Its lint-staged option specifically supports partially staged files and combining quality tools. Ruff provides version-pinned pre-commit hook IDs and places lint auto-fixes before formatting. These support the recommendation to reuse a project's hook manager and keep formatter settings separate from invocation. [Prettier](https://prettier.io/docs/precommit), [Ruff](https://docs.astral.sh/ruff/integrations/#pre-commit)

For an existing Node project using Husky and lint-staged, add the installed Bend executable to its existing lint-staged configuration:

```json
{
  "*.bend": "bend-format fix"
}
```

Lint-staged appends filenames, hides unstaged portions of partially staged files, and by default stages formatter edits automatically. It creates a backup stash and restores state on failure. Unlike pre-commit, its normal default does not hide all tracked unstaged changes; `--hide-unstaged` does. Therefore an unrelated unstaged `.editorconfig` edit can affect formatting unless hidden. Current lint-staged also offers `--hide-all` to hide untracked files. These flags and behavior are version-sensitive; preserve the project's lockfile. [lint-staged](https://github.com/lint-staged/lint-staged)

## Existing Bend sample and distribution limits

The existing [contrib hook](../contrib/hooks/pre-commit) locates a locally built JAR and invokes Java directly. It checks staged Bend bytes through `git show :path` and `check --stdin-path`. It does not snapshot EditorConfig: lookup occurs against the working tree using the real source path. Consequently staged source can be checked with unstaged configuration. It is a contributor-oriented sample, not the simplest installation route for Brew users.

The local unsupported hook intentionally uses whatever `bend-format` is on PATH. Its YAML does not pin the formatter version and `pre-commit autoupdate` does not manage it. CI needs its own explicit installation; using an immutable release archive plus its checksum pins the tool version. A future first-party version-pinned downloadable hook could improve onboarding, but the repository currently publishes no such managed hook package. These distribution conclusions follow from the current repository metadata, not a claim of execution tests.

## Validation

The parent task smoke-tested the configuration with pre-commit 4.6.2 and the released Bend 0.1.12 Linux ARM archive. Configuration validation and hook installation passed. A malformed file was fixed, caused pre-commit exit 1 for modified files, and passed on rerun. A filename containing spaces worked. The read-only `check` alternative rejected malformed staged bytes even when the unstaged working-tree version was formatted, and restored that unstaged content. Evidence: `/tmp/bend-precommit-smoke.py`, fixture `/tmp/bend-precommit-fpp_gmyi`. This does not verify every platform or the separate staged-EditorConfig scenario.
