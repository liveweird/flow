#!/usr/bin/env bash
# The local gates, the way CI runs them, each timed into the shared timings TSV
# (scripts/timings/local-times.mjs record; see .claude/docs/build-times.md "How to measure").
#
#   scripts/gates.sh [server|web|e2e|all]      (default: all)
#
# Stops at the first failing gate and exits with its code.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

which_gates="${1:-all}"
case "$which_gates" in
  server | web | e2e | all) ;;
  *)
    echo "usage: scripts/gates.sh [server|web|e2e|all]" >&2
    exit 2
    ;;
esac

record=(node "$root/scripts/timings/local-times.mjs" record)

server_build() {
  if [ -z "${JAVA_HOME:-}" ] && command -v mise > /dev/null 2>&1; then
    JAVA_HOME="$(mise where java)"
    export JAVA_HOME
  fi
  local orbstack="$HOME/.orbstack/run/docker.sock"
  if [ -z "${DOCKER_HOST:-}" ] && [ -S "$orbstack" ]; then
    export DOCKER_HOST="unix://$orbstack"
  fi
  "${record[@]}" server-build -- "$root/gradlew" cleanTest build
}

web_gates() {
  "${record[@]}" web-gates -- bash -c \
    'cd "$1/web" && npm run lint:api && npm run check:api && npm run lint && npm run knip && npm run test:coverage && npm run build' \
    _ "$root"
}

e2e_static() {
  "${record[@]}" e2e-static -- bash -c \
    'cd "$1/e2e" && npm run lint && npm run knip && npm run typecheck && npm run check:scenarios' \
    _ "$root"
}

summary=()
run_gate() { # <label> <function>
  local label="$1" fn="$2" start code=0
  start=$(date +%s)
  "$fn" || code=$?
  local secs=$(($(date +%s) - start))
  if [ "$code" -ne 0 ]; then
    summary+=("FAIL  $label  ${secs}s  exit $code")
    printf '%s\n' "${summary[@]}"
    exit "$code"
  fi
  summary+=("PASS  $label  ${secs}s")
}

case "$which_gates" in
  server) run_gate server-build server_build ;;
  web) run_gate web-gates web_gates ;;
  e2e) run_gate e2e-static e2e_static ;;
  all)
    run_gate server-build server_build
    run_gate web-gates web_gates
    run_gate e2e-static e2e_static
    ;;
esac

printf '\n%s\n\n' "gates summary"
printf '%s\n' "${summary[@]}"
echo
node "$root/scripts/timings/local-times.mjs" report
