#!/bin/bash
# Run the Sentinel Maestro suite against one device and app id.
# Usage: ./run.sh [-d DEVICE] [-a APP_ID] [-i TAGS] [-x TAGS] [-n NAME] [FLOW_OR_DIR ...]
#   -d  adb serial               (default $DEVICE or emulator-5554)
#   -a  app id                   (default $APP_ID or com.samourai.sentinel.staging; release: com.samourai.sentinel)
#   -i  include tags, comma list (e.g. smoke or p0)
#   -x  exclude tags             (default: blocked)
#   -n  label for the run folder
#   FLOW_OR_DIR defaults to flows/ (ordered by config.yaml)
# Output: runs/<timestamp>-<label>/  junit.xml, summary.md, artifacts/ (screenshots, commands), debug/,
#         logcat.redacted.txt, app.txt. The folder is chmod 700 and scrubbed of secrets on exit.
set -u
SUITE=$(cd "$(dirname "$0")" && pwd)
DEVICE=${DEVICE:-emulator-5554}
APP_ID=${APP_ID:-com.samourai.sentinel.staging}
INCLUDE=""; EXCLUDE="blocked"; NAME="run"
while getopts "d:a:i:x:n:" o; do case $o in
  d) DEVICE=$OPTARG;; a) APP_ID=$OPTARG;; i) INCLUDE=$OPTARG;; x) EXCLUDE=$OPTARG;; n) NAME=$OPTARG;;
  *) sed -n '2,12p' "$0"; exit 2;; esac; done
shift $((OPTIND-1))
TARGETS=("$@"); [ ${#TARGETS[@]} -eq 0 ] && TARGETS=("$SUITE/flows")
CONFIG="$SUITE/config.yaml"

# Paths are env-driven. SENTINEL_QA_HOME holds the helper venv, gRPC stubs and the secrets file.
QA_HOME=${SENTINEL_QA_HOME:-$HOME/.sentinel-qa}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
[ -n "${JAVA_HOME:-}" ] && export PATH=$JAVA_HOME/bin:$PATH
export GRPC_STUB_DIR=${GRPC_STUB_DIR:-$QA_HOME/grpcstub}
HELPER_PY=${HELPER_PY:-$QA_HOME/venv/bin/python}
export QR_PY=${QR_PY:-$HELPER_PY}
export PATH=$HOME/.maestro/bin:$ANDROID_HOME/platform-tools:$PATH
export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true MAESTRO_DISABLE_UPDATE_CHECK=true
SECRETS=${SENTINEL_SECRETS_FILE:-$QA_HOME/secret/sentinel.env}
[ -r "$SECRETS" ] || { echo "missing secrets file $SECRETS (see README)"; exit 2; }
[ "$(stat -c %a "$SECRETS")" = 600 ] || { echo "$SECRETS must be chmod 600"; exit 2; }
adb -s "$DEVICE" get-state >/dev/null 2>&1 || { echo "device $DEVICE not found"; exit 2; }
adb -s "$DEVICE" shell pm path "$APP_ID" >/dev/null 2>&1 || { echo "$APP_ID not installed on $DEVICE"; exit 2; }

TS=$(date +%Y%m%d-%H%M%S); OUT="$SUITE/runs/$TS-$NAME"
umask 077; mkdir -p "$OUT/artifacts" "$OUT/debug"; chmod 700 "$OUT"
# Per-run config: Maestro rejects a flowsOrder naming flows that aren't selected, so order only the
# flows that will run (command-line order for explicit files; else config.yaml order filtered by tags).
CONFIG="$OUT/config.yaml"
python3 - "$SUITE" "$INCLUDE" "$EXCLUDE" "${@}" > "$CONFIG" <<'PY'
import sys, os, yaml
suite, inc, exc, files = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
inc = set(filter(None, inc.split(','))); exc = set(filter(None, exc.split(',')))
base = yaml.safe_load(open(os.path.join(suite, 'config.yaml')))
names = [os.path.basename(f)[:-5] for f in files if f.endswith('.yaml')] or base['executionOrder']['flowsOrder']
keep = []
for n in names:
    p = os.path.join(suite, 'flows', n + '.yaml')
    tags = set(yaml.safe_load(open(p).read().split('\n---')[0]).get('tags') or []) if os.path.exists(p) else set()
    if (inc and not tags & inc) or (tags & exc): continue
    keep.append(n)
cfg = {'executionOrder': {'continueOnFailure': True, 'flowsOrder': keep}}
print(yaml.safe_dump(cfg, sort_keys=False))
PY

# secrets -> MAESTRO_* env only (never on a command line). Maestro reads MAESTRO_* vars itself.
set -a; . "$SECRETS"; set +a
export MAESTRO_SENTINEL_PIN="${SENTINEL_PIN:?SENTINEL_PIN missing in $SECRETS}"
unset SENTINEL_PIN SENTINEL_BACKUP_TEST_PASS 2>/dev/null

# public throwaway test data -> -e KEY=value
HELPER_PORT=${HELPER_PORT:-8977}
ENVARGS=(-e "APP_ID=$APP_ID" -e "HELPER=http://127.0.0.1:$HELPER_PORT")
for f in throwaway.env backup.env; do [ -r "$SUITE/data/$f" ] || { echo "missing data/$f: run scripts/gen_throwaway.py and scripts/make_dummy_backup.py (see data/*.env.example)"; exit 2; }; done
while IFS='=' read -r k v; do [[ -z "$k" || "$k" == \#* ]] && continue; ENVARGS+=(-e "$k=$v"); done < <(cat "$SUITE/data/throwaway.env" "$SUITE/data/backup.env")

# record what is under test
{ echo "date: $(date '+%F %T %Z')"; echo "device: $DEVICE ($(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r'), API $(adb -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "app: $APP_ID"; adb -s "$DEVICE" shell dumpsys package "$APP_ID" | grep -E 'versionName|versionCode' | head -2 | sed 's/^ */  /'
  apk=$(adb -s "$DEVICE" shell pm path "$APP_ID" | head -1 | cut -d: -f2 | tr -d '\r'); echo "  apk sha256: $(adb -s "$DEVICE" exec-out sha256sum "$apk" | cut -d' ' -f1)"
  echo "maestro: $(maestro --version 2>/dev/null | tail -1)"; echo "java: $(java -version 2>&1 | head -1)"; } > "$OUT/app.txt"

cleanup() {
  # stop Maestro first (on Ctrl-C/kill its JVM keeps writing logs), then scrub
  pkill -f "java --enable-native-access=ALL-UNNAMED -classpath $HOME/.maestro/lib" 2>/dev/null; pkill -f "maestro --device $DEVICE test" 2>/dev/null
  for i in 1 2 3 4 5; do pgrep -f "java --enable-native-access=ALL-UNNAMED -classpath $HOME/.maestro/lib" >/dev/null || break; sleep 2; done
  pkill -9 -f "java --enable-native-access=ALL-UNNAMED -classpath $HOME/.maestro/lib" 2>/dev/null
  [ -n "${LCPID:-}" ] && kill "$LCPID" 2>/dev/null
  [ -n "${HPID:-}" ] && kill "$HPID" 2>/dev/null
  # Maestro may still drop a copy of its log under ~/.maestro/tests: move the newest ones into the run and scrub
  find "$HOME/.maestro/tests" -mindepth 1 -maxdepth 1 -newer "$OUT/app.txt" -exec mv {} "$OUT/debug/" \; 2>/dev/null
  if [ -f "$OUT/logcat.raw" ]; then mv "$OUT/logcat.raw" "$OUT/logcat.redacted.txt"; fi
  python3 "$SUITE/scripts/scrub.py" --secrets "$SECRETS" "$OUT" "$HOME/.maestro/tests" >> "$OUT/scrub.txt" 2>&1
  python3 "$SUITE/scripts/verify_scrub.py" --secrets "$SECRETS" "$OUT" >> "$OUT/scrub.txt" 2>&1 || echo "WARNING: verify_scrub found leftovers, see scrub.txt" | tee -a "$OUT/scrub.txt"
  python3 "$SUITE/scripts/summarize.py" "$OUT" > "$OUT/summary.md" 2>>"$OUT/scrub.txt"
  chmod -R go-rwx "$OUT"
  echo "run folder: $OUT"; cat "$OUT/scrub.txt"
}
trap cleanup EXIT

HELPER_PORT=$HELPER_PORT "$HELPER_PY" "$SUITE/scripts/helper.py" & HPID=$!
sleep 1; curl -fs "http://127.0.0.1:$HELPER_PORT/health" >/dev/null || echo "WARN: clipboard helper not running (clipboard flows will fail)"
adb -s "$DEVICE" shell pm grant "$APP_ID" android.permission.CAMERA 2>/dev/null
adb -s "$DEVICE" logcat -c 2>/dev/null
adb -s "$DEVICE" logcat -v threadtime > "$OUT/logcat.raw" 2>/dev/null & LCPID=$!

TAGARGS=(); [ -n "$INCLUDE" ] && TAGARGS+=(--include-tags "$INCLUDE"); [ -n "$EXCLUDE" ] && TAGARGS+=(--exclude-tags "$EXCLUDE")
maestro --device "$DEVICE" test --no-ansi --config "$CONFIG" "${ENVARGS[@]}" "${TAGARGS[@]}" \
  --format junit --output "$OUT/junit.xml" --test-output-dir "$OUT/artifacts" --debug-output "$OUT/debug" \
  "${TARGETS[@]}" 2>&1 | tee "$OUT/console.txt"
RC=${PIPESTATUS[0]}
echo "maestro exit code: $RC" >> "$OUT/app.txt"
exit $RC
