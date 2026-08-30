#!/usr/bin/env bash
set -euo pipefail

is_retryable_lint_failure() {
  grep -Eqi \
    '((NoClassDefFoundError|ClassNotFoundException|Could not initialize class).*(ModifierDeclarationDetector)|ModifierDeclarationDetector.*(NoClassDefFoundError|ClassNotFoundException)|Gradle Worker Daemon.*(disappeared|exited unexpectedly))' \
    "$1"
}

if [ "${1:-}" = --classify ]; then
  is_retryable_lint_failure "${2:?log path required}" && printf 'infrastructure\n' || printf 'deterministic\n'
  exit 0
fi

log="${MOTD_LINT_LOG:-build/android-lint.log}"
mkdir -p "$(dirname "$log")"
tasks=(:app:lintRelease)
[ "${MOTD_LINT_DEBUG:-false}" != true ] || tasks+=(:app:lintDebug)
gradle=(bash "${MOTD_GRADLE:-./gradlew}" "${tasks[@]}" --stacktrace)

for attempt in 1 2; do
  set +e
  "${gradle[@]}" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  set -e
  [ "$status" -ne 0 ] || exit 0
  if [ "$attempt" -eq 1 ] && is_retryable_lint_failure "$log"; then
    echo "Known lint worker/classloader failure; retrying once" >&2
    continue
  fi
  exit "$status"
done
