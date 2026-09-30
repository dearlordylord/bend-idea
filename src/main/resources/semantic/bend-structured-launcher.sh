#!/bin/sh
# Select this launcher as the Bend executable after setting the two paths below.
# Keep this file beside bend-structured-helper.ts.
set -eu
export BEND_NO_TELEMETRY=1
: "${BEND_IDEA_BUN:?Set BEND_IDEA_BUN to a Bun executable}"
: "${BEND_IDEA_BEND_DIR:?Set BEND_IDEA_BEND_DIR to the Bend bend2 directory}"
case "${1:-}" in
  --idea-structured-capabilities)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" capabilities "$BEND_IDEA_BEND_DIR"
    ;;
  --idea-check-capabilities)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" check-capabilities "$BEND_IDEA_BEND_DIR"
    ;;
  --idea-check)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" check "$BEND_IDEA_BEND_DIR" "$2"
    ;;
  --idea-structured-diagnostic)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" diagnostic "$BEND_IDEA_BEND_DIR" "$2"
    ;;
  --idea-structured-goal)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" goal "$BEND_IDEA_BEND_DIR" "$2"
    ;;
  --idea-structured-types)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" types "$BEND_IDEA_BEND_DIR" "$2"
    ;;
  --idea-structured-compare)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" compare "$BEND_IDEA_BEND_DIR" "$2"
    ;;
  --idea-structured-normalize)
    exec "$BEND_IDEA_BUN" "$(dirname "$0")/bend-structured-helper.ts" normalize "$BEND_IDEA_BEND_DIR" "$2" "$3" "$4" "$5"
    ;;
  *)
    exec "$BEND_IDEA_BUN" "$BEND_IDEA_BEND_DIR/main.ts" "$@"
    ;;
esac
