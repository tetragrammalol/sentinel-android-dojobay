#!/bin/bash
# Show per-flow progress of the newest (or given) run: first FAILED command per flow, if any.
R=${1:-$(ls -td "$(dirname "$0")"/../runs/*/ | head -1)}
for d in "$R"/artifacts/*/*/; do f=$(basename "$d")
  fail=$(grep -h "onCommandFinished.*FAILED" "$d"/logs/maestro.log 2>/dev/null | grep -vE "Run flow when|\.yaml FAILED" | head -1 | sed 's/.*onCommandFinished: //' | cut -c1-140)
  echo "$f: ${fail:-ok/running}"; done
