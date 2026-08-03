#!/usr/bin/env bash
# Fast, emulator-only local E2E lifecycle. Every adb call is pinned to the emulator serial; this
# script never installs, clears, reverses ports on, or changes settings on a physical device.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$E2E_DIR/../.." && pwd)"
CMD="${1:-fast}"
shift || true

STATE_DIR="${MOTD_HEADLESS_STATE_DIR:-/tmp/motd-headless}"
STACK_DIR="${MOTD_HEADLESS_STACK_DIR:-/tmp/motd-headless-stack}"
AVD_HOME="${MOTD_HEADLESS_AVD_HOME:-${XDG_CACHE_HOME:-$HOME/.cache}/motd/android-avd}"
AVD_NAME="${MOTD_HEADLESS_AVD_NAME:-motd-api34-headless}"
EMULATOR_PORT="${MOTD_HEADLESS_EMULATOR_PORT:-5556}"
ERGO_PORT="${MOTD_HEADLESS_ERGO_PORT:-16667}"
SOJU_PORT="${MOTD_HEADLESS_SOJU_PORT:-16697}"
SOJU_HTTP_PORT="${MOTD_HEADLESS_SOJU_HTTP_PORT:-16698}"
SERIAL="emulator-${EMULATOR_PORT}"
PID_FILE="$STATE_DIR/emulator.pid"
SERIAL_FILE="$STATE_DIR/emulator.serial"
LOG_FILE="$STATE_DIR/emulator.log"
SEED_PID_FILE="$STATE_DIR/seed.pid"
SEED_LOG_FILE="$STATE_DIR/seed.log"
ARTIFACTS="$E2E_DIR/artifacts/headless"
SYSTEM_IMAGE="system-images;android-34;default;x86_64"

# shellcheck source=test/e2e/harness.sh
. "$E2E_DIR/harness.sh"

export ANDROID_AVD_HOME="$AVD_HOME"

log() { printf '\033[36m[headless-e2e]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m[headless-e2e] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

needs_emulator_tools() {
  case "$CMD" in
    up|fast|full|showcase|status|down|reset) return 0 ;;
    *) return 1 ;;
  esac
}

if needs_emulator_tools && ! command -v emulator >/dev/null 2>&1; then
  exec nix develop "$REPO#emulator" -c "$0" "$CMD" "$@"
fi

adb_e() { adb -s "$SERIAL" "$@"; }

emulator_alive() {
  [ -f "$PID_FILE" ] && kill -0 "$(<"$PID_FILE")" 2>/dev/null &&
    adb_e get-state >/dev/null 2>&1
}

stack_alive() {
  [ -f "$STACK_DIR/ergo.pid" ] && [ -f "$STACK_DIR/soju.pid" ] &&
    kill -0 "$(<"$STACK_DIR/ergo.pid")" 2>/dev/null &&
    kill -0 "$(<"$STACK_DIR/soju.pid")" 2>/dev/null &&
    grep -q '^file-upload fs ' "$STACK_DIR/soju.config" 2>/dev/null
}

ensure_avd() {
  mkdir -p "$STATE_DIR" "$AVD_HOME" "$ARTIFACTS"
  if ! emulator -list-avds | grep -Fxq "$AVD_NAME"; then
    log "creating isolated AVD $AVD_NAME"
    printf 'no\n' | avdmanager create avd --force --name "$AVD_NAME" \
      --package "$SYSTEM_IMAGE" --device pixel_6 >/dev/null
  fi
}

boot_emulator() {
  if emulator_alive; then
    log "$SERIAL already running"
    return
  fi
  [ -r /dev/kvm ] && [ -w /dev/kvm ] || die "/dev/kvm is unavailable; refusing slow software emulation"
  if adb_e get-state >/dev/null 2>&1; then
    die "$SERIAL already belongs to an unmanaged emulator; use MOTD_HEADLESS_EMULATOR_PORT"
  fi
  ensure_avd
  log "booting $AVD_NAME as $SERIAL"
  # Quickboot: load the named snapshot when present and save it on the clean
  # `adb emu kill` in down(). The first boot has no snapshot and cold-boots (the
  # 120s wait below still guards); every boot after a reboot or `down` restores
  # the snapshot instead of paying a full cold boot. App state is irrelevant to
  # the snapshot because fast-suite reinstalls the APKs and pm-clears per method.
  setsid emulator "@$AVD_NAME" -port "$EMULATOR_PORT" -no-window -noaudio \
    -no-boot-anim -gpu swiftshader_indirect -snapshot default-boot >"$LOG_FILE" 2>&1 &
  printf '%s\n' "$!" >"$PID_FILE"
  printf '%s\n' "$SERIAL" >"$SERIAL_FILE"

  local attempt booted
  booted=false
  for attempt in $(seq 1 120); do
    if ! kill -0 "$(<"$PID_FILE")" 2>/dev/null; then
      tail -80 "$LOG_FILE" >&2 || true
      die "emulator exited during boot"
    fi
    if [ "$(adb_e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      booted=true
      break
    fi
    sleep 1
  done
  [ "$booted" = true ] || die "emulator did not boot within 120 seconds (see $LOG_FILE)"
  adb_e shell input keyevent 82 >/dev/null 2>&1 || true
  log "$SERIAL ready"
}

configure_emulator() {
  adb_e shell settings put global window_animation_scale 0 >/dev/null
  adb_e shell settings put global transition_animation_scale 0 >/dev/null
  adb_e shell settings put global animator_duration_scale 0 >/dev/null
  # AOSP can surface benign SystemUI ANR dialogs while the host is packaging a large APK. They do
  # not represent the app under test and must not cover its semantics tree.
  adb_e shell settings put global hide_error_dialogs 1 >/dev/null
  # Keep the system appearance stable; the showcase phase explicitly selects
  # the dark Modus Vivendi app preset through the production settings screen.
  adb_e shell cmd uimode night no >/dev/null 2>&1 || true
  adb_e shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
}

ensure_stack() {
  if stack_alive; then
    log "native soju/ergo stack already running"
    adb_e reverse "tcp:$ERGO_PORT" "tcp:$ERGO_PORT" >/dev/null
    adb_e reverse "tcp:$SOJU_PORT" "tcp:$SOJU_PORT" >/dev/null
    adb_e reverse "tcp:$SOJU_HTTP_PORT" "tcp:$SOJU_HTTP_PORT" >/dev/null
    return
  fi
  log "starting isolated native soju/ergo stack"
  ANDROID_SERIAL="$SERIAL" MOTD_STACK_DIR="$STACK_DIR" \
    MOTD_STACK_PROFILE="${MOTD_STACK_PROFILE:-default}" \
    MOTD_ERGO_PORT="$ERGO_PORT" MOTD_SOJU_PORT="$SOJU_PORT" MOTD_SOJU_HTTP_PORT="$SOJU_HTTP_PORT" \
    "$E2E_DIR/local-stack.sh" up
  # The instrumentation fixture client talks directly to Ergo to create a deterministic gap.
  adb_e reverse "tcp:$ERGO_PORT" "tcp:$ERGO_PORT" >/dev/null
}

ensure_seed_member() {
  if [ -f "$SEED_PID_FILE" ] && kill -0 "$(<"$SEED_PID_FILE")" 2>/dev/null; then
    return
  fi
  if [ "${MOTD_STACK_PROFILE:-default}" = showcase ]; then
    log "holding the deterministic showcase member in #guix and companion channels"
    setsid env ANDROID_SERIAL="$SERIAL" MOTD_STACK_DIR="$STACK_DIR" \
      MOTD_STACK_PROFILE=showcase MOTD_STACK_CHANNEL='#guix' \
      MOTD_ERGO_PORT="$ERGO_PORT" MOTD_SOJU_PORT="$SOJU_PORT" MOTD_SOJU_HTTP_PORT="$SOJU_HTTP_PORT" \
      SEED_HOLD_SECONDS=900 "$E2E_DIR/local-stack.sh" showcase-hold >"$SEED_LOG_FILE" 2>&1 &
  else
    log "holding the deterministic second nick in ##motdtest"
    setsid env ANDROID_SERIAL="$SERIAL" MOTD_STACK_DIR="$STACK_DIR" \
      MOTD_STACK_PROFILE=default \
      MOTD_ERGO_PORT="$ERGO_PORT" MOTD_SOJU_PORT="$SOJU_PORT" MOTD_SOJU_HTTP_PORT="$SOJU_HTTP_PORT" \
      SEED_HOLD_SECONDS=900 "$E2E_DIR/local-stack.sh" seed >"$SEED_LOG_FILE" 2>&1 &
  fi
  printf '%s\n' "$!" >"$SEED_PID_FILE"
}

up() {
  boot_emulator
  configure_emulator
  ensure_stack
  ensure_seed_member
}

fast() {
  local tls_sha256
  up
  tls_sha256="$(MOTD_STACK_DIR="$STACK_DIR" MOTD_SOJU_PORT="$SOJU_PORT" \
    MOTD_SOJU_HTTP_PORT="$SOJU_HTTP_PORT" "$E2E_DIR/local-stack.sh" tls-fingerprint)"
  log "discovering and running isolated fast journeys only on $SERIAL"
  if SERIAL="$SERIAL" FAST_E2E_OUT_DIR="$ARTIFACTS" \
    FAST_E2E_SOJU_HOST=127.0.0.1 FAST_E2E_SOJU_PORT="$SOJU_PORT" \
    FAST_E2E_SOJU_TLS_SHA256="$tls_sha256" \
    FAST_E2E_STACK_KIND=native FAST_E2E_NATIVE_STACK_DIR="$STACK_DIR" \
    FAST_E2E_NATIVE_ERGO_PORT="$ERGO_PORT" FAST_E2E_ERGO_PORT="$ERGO_PORT" \
    nix develop "$REPO" -c "$E2E_DIR/fast-suite.sh" direct; then
    :
  else
    e2e_capture_native_stack_artifacts "$ARTIFACTS" "$STACK_DIR"
    die "fast suite failed; see $ARTIFACTS"
  fi
  log "PASS; emulator and stack remain running for the next iteration"
}

full() {
  up
  log "building shell-runbook E2E APK"
  nix develop "$REPO" -c ./gradlew :app:assembleFossE2e \
    --stacktrace --max-workers=2
  ANDROID_SERIAL="$SERIAL" SERIAL="$SERIAL" \
    MOTD_PKG=io.github.trevarj.motd.debug \
    MOTD_APK="$REPO/app/build/outputs/apk/foss/e2e/app-foss-e2e.apk" \
    MOTD_SOJU_HOST=127.0.0.1 MOTD_SOJU_PORT="$SOJU_PORT" \
    MOTD_SOJU_USER=motd MOTD_SOJU_PASS=motdtest \
    MOTD_NICK=motdadb MOTD_TEST_CHANNEL='##motdtest' MOTD_SECOND_NICK=motdadb2 \
    MOTD_RECONNECT_STACK_KIND=native MOTD_STACK_DIR="$STACK_DIR" \
    MOTD_ERGO_PORT="$ERGO_PORT" MOTD_SOJU_HTTP_PORT="$SOJU_HTTP_PORT" \
    E2E_PHASES="${E2E_PHASES:-a b c d e f g h j v r i}" \
    nix develop "$REPO" -c "$E2E_DIR/runbook.sh"
}

showcase() {
  export MOTD_STACK_PROFILE=showcase
  export MOTD_STACK_CHANNEL='#guix'
  local screenshot_dir="${MOTD_SHOWCASE_SCREENSHOT_DIR:-$REPO/screenshots}"

  # The wrapper owns this emulator and stack, so reset only that isolated pair
  # before producing tracked assets. The release installation is never touched.
  down
  up
  log "building showcase E2E APK"
  nix develop "$REPO" -c ./gradlew :app:assembleFossE2e \
    --stacktrace --max-workers=2
  log "capturing public showcase screenshots into $screenshot_dir"
  ANDROID_SERIAL="$SERIAL" SERIAL="$SERIAL" \
    MOTD_PKG=io.github.trevarj.motd.debug \
    MOTD_APK="$REPO/app/build/outputs/apk/foss/e2e/app-foss-e2e.apk" \
    MOTD_SOJU_HOST=127.0.0.1 MOTD_SOJU_PORT="$SOJU_PORT" \
    MOTD_SOJU_USER=motd MOTD_SOJU_PASS=motdtest \
    MOTD_NICK=motdadb MOTD_TEST_CHANNEL='#guix' MOTD_SECOND_NICK=motdadb2 \
    MOTD_STACK_PROFILE=showcase E2E_SCREENSHOT_DIR="$screenshot_dir" \
    MOTD_RECONNECT_STACK_KIND=native MOTD_STACK_DIR="$STACK_DIR" \
    MOTD_ERGO_PORT="$ERGO_PORT" MOTD_SOJU_HTTP_PORT="$SOJU_HTTP_PORT" \
    E2E_OUT_DIR="$STATE_DIR/showcase-artifacts" \
    E2E_PHASES="${E2E_PHASES:-a s}" \
    nix develop "$REPO" -c "$E2E_DIR/runbook.sh"
}

status() {
  if emulator_alive; then log "$SERIAL running (pid $(<"$PID_FILE"))"; else log "emulator stopped"; fi
  if stack_alive; then log "stack running at 127.0.0.1:$SOJU_PORT"; else log "stack stopped"; fi
}

down() {
  if [ -f "$SEED_PID_FILE" ]; then
    kill "$(<"$SEED_PID_FILE")" 2>/dev/null || true
    rm -f "$SEED_PID_FILE"
  fi
  if [ -f "$SERIAL_FILE" ]; then
    ANDROID_SERIAL="$SERIAL" MOTD_STACK_DIR="$STACK_DIR" \
      MOTD_ERGO_PORT="$ERGO_PORT" MOTD_SOJU_PORT="$SOJU_PORT" MOTD_SOJU_HTTP_PORT="$SOJU_HTTP_PORT" \
      "$E2E_DIR/local-stack.sh" down || true
  fi
  if emulator_alive; then
    log "stopping $SERIAL"
    adb_e emu kill >/dev/null 2>&1 || kill "$(<"$PID_FILE")" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$(<"$PID_FILE")" 2>/dev/null || break
      sleep 1
    done
  fi
  rm -f "$PID_FILE" "$SERIAL_FILE"
}

reset() {
  down
  log "removing isolated AVD and stack state"
  avdmanager delete avd --name "$AVD_NAME" >/dev/null 2>&1 || true
  rm -rf "$STACK_DIR" "$STATE_DIR" "$AVD_HOME"
}

case "$CMD" in
  up) up ;;
  fast) fast ;;
  full) full ;;
  showcase) showcase ;;
  status) status ;;
  down) down ;;
  reset) reset ;;
  *) die "usage: $0 {up|fast|full|showcase|status|down|reset}" ;;
esac
