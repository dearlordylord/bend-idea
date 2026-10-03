#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
shopt -s nullglob
tools=("$repo_root"/build/libs/bend-format-tool-*.jar)
if ((${#tools[@]} != 1)); then
  printf 'Expected exactly one built Bend format tool; clean build/libs and run ./gradlew buildFormatTool.\n' >&2
  exit 2
fi
tool="${tools[0]}"
java_executable="${JAVA_HOME:+$JAVA_HOME/bin/}java"

status=0
while IFS= read -r -d '' path; do
  if "$java_executable" -jar "$tool" check "$repo_root/$path"; then
    :
  else
    result=$?
    if ((result > status)); then status=$result; fi
  fi
done < <(git ls-files -z -- '*.bend')
exit "$status"
