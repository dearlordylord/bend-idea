#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
pin_file="$script_dir/bend-test-toolchain.properties"

die() {
  printf 'Bend test input error: %s\n' "$1" >&2
  exit 1
}

read_pin() {
  local key="$1"
  local value
  value="$(awk -F= -v key="$key" '$1 == key { print $2; count++ } END { if (count != 1) exit 1 }' "$pin_file")" \
    || die "expected exactly one '$key' in $pin_file"
  [[ -n "$value" ]] || die "empty '$key' in $pin_file"
  printf '%s' "$value"
}

expected_bend_commit="$(read_pin bend.commit)"
expected_bun_version="$(read_pin bun.version)"
expected_bun_revision="$(read_pin bun.revision)"

[[ "$expected_bend_commit" =~ ^[0-9a-f]{40}$ ]] || die "bend.commit must be a full 40-character lowercase Git SHA"
[[ "$expected_bun_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "bun.version must be an exact release version"

[[ -n "${BEND_TEST_COMPILER_DIR:-}" ]] || die "BEND_TEST_COMPILER_DIR is required; point it to the supplied pinned Bend checkout"
[[ -n "${BEND_TEST_BUN:-}" ]] || die "BEND_TEST_BUN is required; point it to the pinned Bun executable"
[[ "$BEND_TEST_COMPILER_DIR" = /* ]] || die "BEND_TEST_COMPILER_DIR must be absolute"
[[ "$BEND_TEST_BUN" = /* ]] || die "BEND_TEST_BUN must be an absolute executable path"

[[ -d "$BEND_TEST_COMPILER_DIR" ]] || die "Bend checkout is missing: $BEND_TEST_COMPILER_DIR"
compiler_dir="$(cd -- "$BEND_TEST_COMPILER_DIR" 2>/dev/null && pwd -P)" \
  || die "cannot resolve Bend checkout: $BEND_TEST_COMPILER_DIR"
checkout_root="$(git -C "$compiler_dir" rev-parse --show-toplevel 2>/dev/null)" \
  || die "BEND_TEST_COMPILER_DIR is not a Git checkout: $compiler_dir"
[[ "$checkout_root" = "$compiler_dir" ]] || die "BEND_TEST_COMPILER_DIR must name the checkout root ($checkout_root)"

observed_bend_commit="$(git -C "$compiler_dir" rev-parse --verify HEAD 2>/dev/null)" \
  || die "cannot read Bend checkout HEAD: $compiler_dir"
[[ "$observed_bend_commit" = "$expected_bend_commit" ]] || \
  die "Bend revision mismatch: expected $expected_bend_commit, observed $observed_bend_commit"
if [[ -n "$(git -C "$compiler_dir" status --porcelain --untracked-files=all)" ]]; then
  die "Bend checkout has tracked or untracked changes: $compiler_dir"
fi
[[ -f "$compiler_dir/bend2/main.ts" ]] || die "Bend CLI is missing: $compiler_dir/bend2/main.ts"
[[ -f "$compiler_dir/bend2/base.bend" ]] || die "Bend Base is missing: $compiler_dir/bend2/base.bend"

[[ -x "$BEND_TEST_BUN" ]] || die "Bun is missing or not executable: $BEND_TEST_BUN"
observed_bun_version="$("$BEND_TEST_BUN" --version 2>&1)" \
  || die "could not run Bun version probe: $BEND_TEST_BUN"
[[ "$observed_bun_version" = "$expected_bun_version" ]] || \
  die "Bun version mismatch: expected $expected_bun_version, observed $observed_bun_version"
observed_bun_revision="$("$BEND_TEST_BUN" --revision 2>&1)" \
  || die "could not run Bun revision probe: $BEND_TEST_BUN"
[[ "$observed_bun_revision" = "$expected_bun_revision" ]] || \
  die "Bun revision mismatch: expected $expected_bun_revision, observed $observed_bun_revision"

printf 'Bend source: https://github.com/bendlang/bend\n'
printf 'Bend revision: %s\n' "$observed_bend_commit"
printf 'Bend checkout: %s\n' "$compiler_dir"
printf 'Bun executable: %s\n' "$BEND_TEST_BUN"
printf 'Bun version: %s\n' "$observed_bun_version"
printf 'Bun revision: %s\n' "$observed_bun_revision"
