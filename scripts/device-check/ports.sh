#!/usr/bin/env bash
#
# ports.sh — the TCP probes of device-check.sh.
#
# WHY a module of its own: a TCP connect is the only check that has to be
#      defended against a kernel that waits for minutes, and that defence has
#      to be in one place.
#
# This file is sourced by scripts/device-check.sh — it must not be executed
# directly.
#

# ── the TCP probe ───────────────────────────────────────────────────────────
#
# WHY /dev/tcp and not netcat: netcat is not installed on a stock macOS, and
#      the shell can open a socket itself. The tool therefore needs no tool
#      beyond bash for this check.
#
# WHY the watchdog: the kernel retries a blocked TCP connect for minutes. The
#      owner waits five seconds and then goes for coffee — the check has to
#      end on its own, in the time the owner agreed to wait.
tcp_probe() {
  local ip="$1" port="$2" pid waited=0
  ( exec 3<>"/dev/tcp/${ip}/${port}" ) >/dev/null 2>&1 &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$TIMEOUT" ]; then
      kill "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  wait "$pid" 2>/dev/null && return 0
  return 1
}
