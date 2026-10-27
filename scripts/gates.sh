#!/usr/bin/env bash
# The local gates, the way CI runs them, each timed into the shared timings TSV
# (scripts/timings/local-times.mjs record; see .claude/docs/build-times.md "How to measure").
#
#   scripts/gates.sh [server|web|e2e|all]      (default: all)
#
# Stops at the first failing gate and exits with its code.
#
# What the three stages cover beyond the build/test gates: web = API-contract, lint, knip, shuffled coverage,
# build; e2e = the Docker-free statics (lint, knip, typecheck, scenario parity, `test:setup`) plus the
# toolchain-pins drift check (scripts/check-toolchain-pins.mjs).
#
# CI-ONLY on purpose (.claude/docs/ci.md "Local gates vs CI"), none of them is a gate a code change can fail on its own:
#   - `npm audit` (web runtime deps, e2e full tree) and the HIGH/CRITICAL Gradle lockfile vulnerability scan: they
#     read a live advisory database, so the result changes overnight without a commit and needs the network;
#   - kubeconform over k8s/ (k8s-static): needs a downloaded, checksum-pinned binary and the schema repository;
#   - the Trivy image scan and the AppCDS archive check: need a built image (master only).
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

# The fork count the server gate runs with (-Pforks; default 2 = CI's, build-times.md WHY 44: one fork outgrew the
# 6-min alarm; FLOW_FORKS=1 for the single-JVM run). Past 1 the fork-balance report follows the gate (WHY 18).
server_forks="${FLOW_FORKS:-2}"

server_build() {
  if [ -z "${JAVA_HOME:-}" ] && command -v mise > /dev/null 2>&1; then
    JAVA_HOME="$(mise where java)"
    export JAVA_HOME
  fi
  local orbstack="$HOME/.orbstack/run/docker.sock"
  if [ -z "${DOCKER_HOST:-}" ] && [ -S "$orbstack" ]; then
    export DOCKER_HOST="unix://$orbstack"
  fi
  # `|| return`: run_gate calls this in an `||` context, where bash ignores `set -e` inside the function, so without it a
  # failed build fell through to the `if` below and the function returned 0 — the gate printed PASS for a red build.
  "${record[@]}" server-build -- "$root/gradlew" cleanTest check :server:installDist -Pforks="$server_forks" || return
  if [ "$server_forks" -gt 1 ]; then
    node "$root/scripts/timings/local-times.mjs" slow-tests server/build/test-results/test \
      --forks "$server_forks" --weights server/test-weights.tsv --top-classes 5 --top-tests 1
  fi
}

web_gates() {
  # The shuffle seed is printed (CI writes it to the job summary), so a shuffle-order failure can be replayed with
  # `cd web && npm run test:coverage -- --sequence.shuffle --sequence.seed=<seed>`.
  local seed="$RANDOM$RANDOM"
  echo "vitest shuffle seed: $seed"
  "${record[@]}" web-gates -- bash -c \
    'cd "$1/web" && npm run lint:api && npm run check:api && npm run lint && npm run knip && npm run test:coverage -- --sequence.shuffle --sequence.seed="$2" && npm run build' \
    _ "$root" "$seed"
}

e2e_static() {
  "${record[@]}" e2e-static -- bash -c \
    'cd "$1/e2e" && npm run lint && npm run knip && npm run typecheck && npm run check:scenarios && npm run test:setup && node "$1/scripts/check-toolchain-pins.mjs"' \
    _ "$root"
}

summary=()
run_gate() { # <label> <function>
  local label="$1" fn="$2" start code=0
  start=$(date +%s)
  # `set -e` does not apply inside "$fn" here (it is the left side of `||`): each gate function must return its own
  # failing status explicitly (`|| return`) wherever a later command could mask it.
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
