#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
tool="$repo_root/build/libs/bend-format-tool-0.1.8.jar"
java_executable="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [[ ! -f "$tool" ]]; then
  printf 'Bend format tool 0.1.8 is unavailable; run ./gradlew buildFormatTool first.\n' >&2
  exit 2
fi

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
