# Bend formatter and style checker

The console tool checks formatting and can fix it using the same conservative policy as **Reformat Code**. It reads local source files and EditorConfig; it does not perform semantic linting or compiler checking.

## Install `bend-format`

On **macOS or Linux**, install with [Homebrew](https://brew.sh/):

```sh
brew install dearlordylord/tap/bend-format
```

Then [use `bend-format`](#format-a-project) directly from your project directory. To update later, run `brew update` followed by `brew upgrade bend-format`. To remove it, run `brew uninstall bend-format`.

## Install from an archive

On **Windows**, or if you prefer a manual installation, download the archive for your machine from the [latest release](https://github.com/dearlordylord/bend-idea/releases/latest) and extract it:

| Your machine | Download |
|---|---|
| Linux Intel/AMD 64-bit | [Linux x64](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.13/bend-format-0.1.13-linux-x64.tar.gz) |
| Linux ARM64 | [Linux ARM64](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.13/bend-format-0.1.13-linux-aarch64.tar.gz) |
| macOS Intel | [macOS Intel](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.13/bend-format-0.1.13-macos-x64.tar.gz) |
| macOS Apple Silicon | [macOS Apple Silicon](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.13/bend-format-0.1.13-macos-aarch64.tar.gz) |
| Windows Intel/AMD 64-bit | [Windows x64](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.13/bend-format-0.1.13-windows-x64.zip) |

Keep the extracted directory together and add its `bin` directory to your PATH:

- **Linux/macOS:** move the extracted directory to `~/tools/bend-format`, then add `export PATH="$HOME/tools/bend-format/bin:$PATH"` to your shell profile (`~/.zshrc` or `~/.bashrc`). Open a new terminal.
- **Windows:** move the extracted directory to a permanent location, open **Edit environment variables for your account → Path → Edit → New**, and add the full path to its `bin` directory. Open a new terminal. PowerShell and Command Prompt both accept `bend-format`.

To try it before changing PATH, open a terminal in the extracted directory and run `./bin/bend-format --version` on Linux/macOS, or `bin\bend-format.cmd --version` in Windows Command Prompt. If the path contains spaces, quote it; in PowerShell use `& 'C:\path to\bend-format\bin\bend-format.cmd' --version`.

## Format a project

Run these commands from your project directory:

```sh
bend-format --version
bend-format check src/main.bend src/types.bend
bend-format fix src/main.bend src/types.bend
```

The tool works offline after installation. `check` reports files that need formatting; `fix` applies safe formatting changes.

Pass one or more explicit `.bend` paths. `check` never writes files or the Git index. `fix` changes only safe working-tree files and leaves unavailable files intact. Files in a batch are processed independently, even if another file is unavailable.

| Exit | `check` | `fix` |
|---|---|---|
| `0` | All files conform. | All files conform or were safely formatted. |
| `1` | At least one file would change. | — |
| `2` | A file is unavailable or the command cannot run. | A file is unavailable or the command cannot run; safe files may already have been formatted. |

## EditorConfig and wrapping

Place an `.editorconfig` in your project; the IDE formatter and console tool share these settings:

```ini
root = true

[*.bend]
indent_style = space
indent_size = 2
tab_width = 4
bend_max_line_length = 100
```

- `bend_max_line_length` accepts a positive integer or `off`. Absent or `unset` uses the default **100 visual columns**. `off` preserves existing breaks while allowing safe spacing. Invalid values make formatting unavailable. This is a Bend-specific extension, not a standard EditorConfig property.
- `indent_style`, `indent_size` (including `tab`) and `tab_width` follow shared indentation decoding. Tab width controls visual tab stops independently of space indentation; tab indentation uses tabs plus any remaining spaces. Unsupported standard-property values are ignored individually, preserving other valid settings.
- Nested and inherited overrides work, including sections that set only the line width. When IntelliJ’s EditorConfig support is disabled in Code Style settings, Bend typing and formatting use IDE defaults. The console tool always reads local EditorConfig files. If the optional IDE EditorConfig plugin is absent, Bend retains local property lookup. Generic save properties remain owned by IntelliJ.

Wrapping applies to complete constructor patterns, constructor expressions, calls, definition parameter lists, tuples and list literals. An indented result arrow on the line after a definition’s parameter list is recognized and preserved. Arrays and indexes remain atomic. Recognized overlong multiline lists reflow; fitting multiline grouping remains intact. Names, literals, operators, dependent annotations and arbitrary proof terms stay intact, even when they exceed the soft limit. Comments, line endings and the presence or absence of a final newline are preserved. Header-looking text inside multiline literals remains literal text.

Incomplete source, unsupported layout, unsafe comment attachment, significant newlines that could change call/index parsing, and mixed line endings requiring inserted breaks yield `unavailable` without edits to that file. The formatter does not reflow arbitrary expressions, imports or comments.

In IntelliJ, invoke **Reformat Code** on the whole file, or use **Select All → Reformat Code**; omitting surrounding whitespace or the final newline from that selection still allows wrapping. Partial selections apply only safe horizontal spacing. The opt-in **Actions on Save** and **Commit Checks** use the same formatter. Enter and Backspace stay conservative where tab normalization could change physical body ownership. See [manual feature verification](manual-verification.md).

## Pre-commit hooks

Commit your `.editorconfig` and `.pre-commit-config.yaml` alongside your source files. EditorConfig defines formatting settings; the hook runs the formatter.

On macOS or Linux, install the tools:

```sh
brew install dearlordylord/tap/bend-format pre-commit
```

Add `.pre-commit-config.yaml` to the project root:

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

Enable the hook for your checkout and check existing files:

```sh
pre-commit install
pre-commit run --all-files
```

On each commit, pre-commit passes the staged `.bend` filenames to the formatter and temporarily hides tracked unstaged changes. If formatting changes a file, the commit stops: review the changes, stage the intended edits with `git add`, and retry. For a read-only gate, change `entry` to `bend-format check`. Each developer enables the hook after cloning. See [pre-commit documentation](https://pre-commit.com/) for installation on other platforms.

`language: unsupported` runs the installed command from PATH; it does not install or pin `bend-format`. This name requires pre-commit 4.4.0 or newer. Keep formatting configuration tracked: untracked or ignored EditorConfig files can still affect lookup.

### Existing Husky and lint-staged projects

Add this mapping to your existing lint-staged configuration:

```json
{
  "*.bend": "bend-format fix"
}
```

The formatter must be installed on PATH. lint-staged normally stages formatter changes automatically and hides unstaged portions of partially staged files. Its default does not hide all unrelated unstaged configuration changes. See [lint-staged](https://github.com/lint-staged/lint-staged) for options; retain the project's locked dependency versions.

## Continuous integration

Install a fixed formatter version in CI, then run `pre-commit run --all-files` with the configuration above. Pin the release archive and verify its SHA-256 against the release checksum manifest; the local hook's configuration alone does not pin the formatter version. For a CI gate that never edits source, use `entry: bend-format check`.

For contributors building this repository, the [all-tracked-files script](../ci/bend-format-all-tracked.sh) checks working-tree content using the built JAR; the [CI example](examples/bend-format-ci.yml) builds the tool and runs that script.

The contributor [Git hook](../contrib/hooks/pre-commit) also uses the built JAR. It checks staged source bytes, including partially staged files, while reading EditorConfig from the working tree. After building the tool, copy it into `.git/hooks/pre-commit` and make it executable. Choose one hook setup for your project; the pre-commit configuration above is the simplest option for users of the installed CLI.

## Alternative download and building from source

`--version` reads packaged build metadata, independent of the JAR filename. Missing or blank metadata exits `2`; formatting commands remain usable.

For users who already manage Java, the smaller [portable JAR](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.13/bend-format-tool-0.1.13.jar) requires Java 21 or newer:

```sh
java -jar bend-format-tool-0.1.13.jar check src/main.bend
java -jar bend-format-tool-0.1.13.jar fix src/main.bend
```

The standard archives include a private Java runtime and do not use system Java or JAVA_HOME. Linux builds target glibc distributions and are tested on Ubuntu 22.04; Alpine/musl and Windows ARM64 are not supported. macOS archives are not notarized. Each archive records its runtime provenance and includes license notices. Verify downloads against `BEND-FORMAT-SHA256SUMS.txt` on the release page. To update, replace the extracted directory with the new version and keep PATH pointed at its `bin` directory.

Build a runtime archive for your current OS/architecture with JDK 21 and Python 3.12+ using `./gradlew buildFormatDistribution`; validate the extracted archive with `./gradlew testFormatDistribution`. Select a non-default Python executable with `-PformatDistributionPython=/path/to/python3.12`. Output is under `build/distributions/bend-format/`. Packaging derives the required Java modules with `jdeps`, includes runtime/dependency notices and records runtime provenance. The [distribution workflow](../.github/workflows/formatter-distributions.yml) builds and tests each native platform against an existing release JAR before optionally attaching all archives. See the [packaging research](../research/bend-format-cli-distribution.md) for the decision and tradeoffs.

Build the portable JAR from source with JDK 21 using `./gradlew buildFormatTool`. Select the artifact by its generated name rather than hardcoding a release version:

```bash
shopt -s nullglob
tools=(build/libs/bend-format-tool-*.jar)
if ((${#tools[@]} != 1)); then
  echo "Expected exactly one built formatter; clean build/libs and rebuild." >&2
  exit 2
fi
tool="${tools[0]}"
java -jar "$tool" --version
java -jar "$tool" check path/to/main.bend
```

