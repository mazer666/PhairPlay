#!/usr/bin/env bash
#
# common.sh — the shared state, the output format and the small helpers of
#              device-check.sh.
#
# WHY a module of its own: the tool answers four questions in one run and
#      prints every answer in the same shape — a verdict, what the verdict
#      means and what to do next. The shape and the result state live here, so
#      the four checks cannot drift apart, and the entry point stays small
#      enough to be read in one go.
#
# This file is sourced by scripts/device-check.sh. It is not a program of its
# own and must not be executed directly.
#

# ── the result state ────────────────────────────────────────────────────────
#
# WHY words and not numbers: every check has three states and the summary has
#      to name the state in words. "skipped" means the check was not asked
#      for — it is neither a success nor a failure.
RES_SSDP="skipped"
RES_DESCRIPTION="skipped"
RES_ADDRESS="skipped"
RES_AIRPLAY="skipped"
CHECKS_RUN=0
CHECKS_OK=0
CHECKS_FAILED=0

# What the discovery found.
DISC_LOCATION=""
DISC_SERVER=""
DISC_USN=""
DISC_ST=""
DISC_SOURCE=""
DISC_SAMSUNG="no"
DISC_SAMSUNG_ST=""
ANSWER_COUNT=0
PROBE_ERROR=""

# What the description document holds.
DESC_STATUS=""
DESC_ERROR=""
DESC_FRIENDLY_NAME=""
DESC_UDN=""
DESC_HAS_ROOT=0
DESC_HAS_DEVICE=0
DESC_SERVICES=""
DESC_MISSING=""

# The addresses that are compared with each other.
LOCATION_IP=""
LOCATION_PORT=""
EXPECTED_IP=""
EXPECTED_SOURCE=""
LOCAL_IP=""
LOCAL_IP_OTHERS=""

# ── the constants of the protocol ───────────────────────────────────────────
#
# WHY the unit separator: the answers of the discovery are written to a table
#      with one line per answer. The unit separator cannot occur in a header
#      of an SSDP answer, so it is safe as the field separator.
SEP="$(printf '\037')"

# The search target of a UPnP MediaRenderer -- the device PhairPlay is.
MEDIA_RENDERER_ST="urn:schemas-upnp-org:device:MediaRenderer:1"

# ── the scratch directory ───────────────────────────────────────────────────
#
# WHY a directory: the description document, the raw answers of the probe and
#      the error output of curl are written to files, because a pipe cannot
#      carry both the payload and the exit code of a command.
dc_init_temp() {
  TMP="$(mktemp -d 2>/dev/null || mktemp -d -t phairplay)"
  trap 'rm -rf "$TMP"' EXIT INT TERM
}

# ── the output format ───────────────────────────────────────────────────────
#
# WHY one format for all four checks: the owner reads the output of a failed
#      run in a hurry. The same four lines per check make it possible to find
#      the verdict, the meaning and the next step without reading the whole
#      output.
section() {
  printf '\n[%s] %s\n' "$1" "$2"
}

field() {
  printf '        %-14s: %s\n' "$1" "$2"
}

verdict() {
  printf '      -> %s\n' "$1"
}

meaning() {
  printf '      Meaning: %s\n' "$1"
}

action() {
  printf '      Action : %s\n' "$1"
}

# The result of the checks is the single source of truth for the summary:
# "ok", "fail" or "skipped" (the check was not requested).
count_results() {
  local value
  CHECKS_RUN=0
  CHECKS_OK=0
  CHECKS_FAILED=0
  for value in "$RES_SSDP" "$RES_DESCRIPTION" "$RES_ADDRESS" "$RES_AIRPLAY"; do
    case "$value" in
      ok)
        CHECKS_RUN=$((CHECKS_RUN + 1))
        CHECKS_OK=$((CHECKS_OK + 1))
        ;;
      fail)
        CHECKS_RUN=$((CHECKS_RUN + 1))
        CHECKS_FAILED=$((CHECKS_FAILED + 1))
        ;;
    esac
  done
}

# ── the addresses of this laptop ───────────────────────────────────────────
#
# WHY the owner has to see them: a laptop often has a Wi-Fi address, a VPN
#      address and a container address at the same time, and only the Wi-Fi
#      one belongs to the network of the television.
local_addresses() {
  if command -v ip >/dev/null 2>&1; then
    ip -4 -o addr show scope global 2>/dev/null |
      awk '{ split($4, part, "/"); print part[1] }'
  elif command -v ifconfig >/dev/null 2>&1; then
    ifconfig 2>/dev/null | awk '/inet [0-9]/ { print $2 }' | grep -v '^127\.' || true
  else
    hostname -I 2>/dev/null | tr ' ' '\n' | grep -v '^127\.' || true
  fi
}

detect_local_addresses() {
  local all count
  all="$(local_addresses)"
  count="$(printf '%s\n' "$all" | grep -c . || true)"
  LOCAL_IP="$(printf '%s\n' "$all" | head -1)"
  if [ "$count" -gt 1 ]; then
    LOCAL_IP_OTHERS="$(printf '%s\n' "$all" | tail -n +2 | tr '\n' ',' | sed 's/,$//')"
  fi
}

# ── the address of a URL ────────────────────────────────────────────────────
#
# WHY the script needs it: the LOCATION header names the host the description
#      document lives on. The host part of that URL is the address the
#      renderer claims to have — the thing that is compared with the address
#      the renderer really has.
url_host() {
  local url="$1" rest host
  rest="${url#*://}"
  rest="${rest%%/*}"
  rest="${rest##*@}"
  case "$rest" in
    \[*\]*) host="${rest%%\]*}" ;;
    *:*) host="${rest%%:*}" ;;
    *) host="$rest" ;;
  esac
  printf '%s' "$host"
}

url_port() {
  local url="$1" rest
  rest="${url#*://}"
  rest="${rest%%/*}"
  rest="${rest##*@}"
  case "$rest" in
    \[*\]*)
      rest="${rest#*\]}"
      case "$rest" in
        :*) printf '%s' "${rest#:}" ;;
        *) printf '80' ;;
      esac
      ;;
    *:*) printf '%s' "${rest##*:}" ;;
    *) printf '80' ;;
  esac
}

# WHY the guard: the LOCATION header comes from a device in the network, so it
#      may name any host at all. The script fetches only addresses of the
#      local network, so a broken or hostile device cannot make the laptop
#      talk to a third party.
is_local_address() {
  local ip="$1"
  case "$ip" in
    127.*|10.*|192.168.*|169.254.*|localhost) return 0 ;;
    172.1[6-9].*|172.2[0-9].*|172.3[01].*) return 0 ;;
  esac
  [ "$ip" = "$EXPECTED_IP" ] && return 0
  return 1
}
