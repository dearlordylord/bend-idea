# Formatting workflow verification

Editor fixtures exercise Enter, Backspace and Reformat Code with parent and nested EditorConfig, unset, spaces, tabs and differing tab width. The shared formatter tests cover idempotence, source preservation and pinned compiler comparisons. They do not automate IntelliJ’s Actions on Save or Commit Checks dialogs; no manual execution of those dialogs is claimed here.

## Manual IDE scenario

Use the supported IDEA version with Bend2 installed and a temporary Git project. Put `root = true`, `[*.bend]`, `indent_style = space` and `indent_size = 4` in the project `.editorconfig`. In a nested directory, override `[*.bend]` with `indent_style = tab`, `indent_size = 4` and `tab_width = 4`.

1. Create a parent and nested Bend file containing `def f(x: U32,y: U32) -> U32:` followed by a two-space-indented `x`. Select both files and invoke Reformat Code. Expect comma spacing in both, four spaces in the parent and one tab in the nested file. Repeat through the directory action and confirm no further change.
2. Enable Reformat Code under Settings → Tools → Actions on Save. Restore the original whitespace in both files and save. Expect the same per-file results. Disable the option and verify saving does not invoke Bend reformatting.
3. Enable Reformat Code in Commit Checks. Stage/edit the two files and inspect the IDE’s commit preview after running checks. Expect the same formatting without declaration reordering. Cancel the commit and inspect the working tree; this action is distinct from the supplied non-mutating staged Git hook.
4. Repeat with an unfinished declaration and comments/string literals. Unsupported regions must remain unchanged. Verify Undo after explicit formatting.
5. Set generic final-newline, trailing-whitespace and line-ending options through IntelliJ/EditorConfig and save. Confirm those platform options coexist with Bend formatting. Bend’s formatter does not own those save policies.

Record IDEA version, plugin version, observed text, and which actions were exercised before claiming manual verification. A successful headless Reformat Code fixture does not establish execution of the save/commit dialogs.
