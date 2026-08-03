#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
. "$ROOT/test/e2e/fast-suite-privacy.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/required-e2e"
printf '{"phase":"launcher_started"}\n' >"$scratch/fixture.jsonl"
printf '{"test":"RequiredHeadlessE2eTest_send"}\n' >"$scratch/required-e2e/started.jsonl"
e2e_audit_required_artifacts "$scratch"
# Journey-owned timeline snapshots live under per-label/per-outcome directories, so the audit has
# to accept the allowlisted name at any depth and still judge its contents.
snapshot="$scratch/required-e2e/RequiredHeadlessE2eTest_send/newest_row/timeout"
mkdir -p "$snapshot"
printf '{"schema":"timeline-newest-row/1","target":{"tag":"chat_message_abc","pagingKey":119}}\n' \
  >"$snapshot/timeline.json"
e2e_audit_required_artifacts "$scratch"
printf '{"message":"sentinel"}\n' >"$snapshot/timeline.json"
if e2e_audit_required_artifacts "$scratch" >/dev/null 2>&1; then
  echo "privacy audit accepted message sentinel inside a timeline snapshot" >&2
  exit 1
fi
printf '{"schema":"timeline-newest-row/1"}\n' >"$snapshot/timeline.json"
printf '{"message":"sentinel"}\n' >"$scratch/required-e2e/semantics.json"
if e2e_audit_required_artifacts "$scratch" >/dev/null 2>&1; then
  echo "privacy audit accepted message sentinel" >&2
  exit 1
fi
