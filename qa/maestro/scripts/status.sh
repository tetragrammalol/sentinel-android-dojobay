#!/bin/bash
# Per-flow status of a run (first failed command), plus whether Maestro is still running.
R=${1:-$(ls -td "$(dirname "$0")"/../runs/*/ | head -1)}
pgrep -f "java --enable-native-access=ALL-UNNAMED -classpath $HOME/.maestro/lib" >/dev/null && echo "maestro: running" || echo "maestro: finished"
n=0; for d in "$R"/artifacts/*/*/; do n=$((n+1)); f=$(basename "$d")
 x=$(grep -h "onCommandFinished.*FAILED" "$d"/logs/maestro.log 2>/dev/null | grep -vE "Run flow when|\.yaml FAILED" | head -1 | sed 's/.*onCommandFinished: //' | cut -c1-110)
 [ -n "$x" ] && echo "$f: FAIL $x"; done; echo "flows started: $n (last: $(basename "$(ls -td "$R"/artifacts/*/*/ | head -1)"))"
