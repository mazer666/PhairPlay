#!/usr/bin/env bash
#
# device-check.sh — find out from a laptop whether the television sees PhairPlay.
#
# WHY: the owner has to answer one question before every playback test: "does the
#      television see the renderer at all?". Without a command for it, every failed
#      attempt costs a guess — the wrong Wi-Fi, a firewall, a stopped service or an
#      old IP address in the description URL. This script replaces the guess with
#      four measurements, each with a verdict, a meaning and a next step.
#
# WHAT IS CHECKED (always in this order)
#   1. SSDP discovery  — is the renderer visible in the network?
#   2. Description     — can a control point read the device description?
#   3. Description URL — does it point at the address the renderer really has?
#   4. AirPlay port    — is the RTSP port 7000 reachable?
#
# USAGE
#   ./scripts/device-check.sh [--all | --ssdp | --description | --address | --airplay]
#                             [--host <ip>] [--location <url>]
#                             [--ssdp-target <host[:port]>] [--timeout <seconds>]
#
#   --all             run every check (the default when no check is named)
#   --ssdp            run the discovery only
#   --description     run the description document only
#   --address         run the comparison of the addresses only
#   --airplay         run the AirPlay port check only
#   --host <ip>       the address the television really has
#   --location <url>  the description URL, when it is already known
#   --ssdp-target <host[:port]>
#                     where the M-SEARCH goes (default: the SSDP multicast group)
#   --timeout <seconds>
#                     how long a single check may wait (default: 3)
#
# EXIT CODES
#   0  OK           every check passed
#   1  PARTIAL      some checks passed, some did not
#   2  UNREACHABLE  the renderer did not answer at all
#   64 usage error (unknown option)
#
# DEPENDENCIES
#   bash and curl. The SSDP probe uses python3 (macOS: xcode-select --install).
#   Nothing outside the local network is contacted: only the SSDP multicast
#   group and the description URL that the television itself advertised.
#
# The checks live in scripts/device-check/*.sh and are sourced by this file.
#
# ===== END OF HEADER =====

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/device-check"

if [ ! -d "$MODULE_DIR" ]; then
  printf 'ERROR: the modules of device-check.sh are missing (%s).\n' "$MODULE_DIR" >&2
  exit 70
fi

# shellcheck source=scripts/device-check/common.sh
. "$MODULE_DIR/common.sh"
# shellcheck source=scripts/device-check/ssdp.sh
. "$MODULE_DIR/ssdp.sh"
# shellcheck source=scripts/device-check/description.sh
. "$MODULE_DIR/description.sh"
# shellcheck source=scripts/device-check/ports.sh
. "$MODULE_DIR/ports.sh"
# shellcheck source=scripts/device-check/report.sh
. "$MODULE_DIR/report.sh"

# ── options ────────────────────────────────────────────────────────────────
TIMEOUT=3
HOST=""
LOCATION=""
SSDP_TARGET="239.255.255.250"
RUN_SSDP=0
RUN_DESCRIPTION=0
RUN_ADDRESS=0
RUN_AIRPLAY=0
CHECK_SELECTED=0

usage() {
  sed -n '2,/^# ===== END OF HEADER =====/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

die_usage() {
  printf 'ERROR: %s\n' "$1" >&2
  printf 'Try "%s --help" for usage.\n' "$0" >&2
  exit 64
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --all)
      RUN_SSDP=1
      RUN_DESCRIPTION=1
      RUN_ADDRESS=1
      RUN_AIRPLAY=1
      CHECK_SELECTED=1
      shift
      ;;
    --ssdp)
      RUN_SSDP=1
      CHECK_SELECTED=1
      shift
      ;;
    --description)
      RUN_DESCRIPTION=1
      CHECK_SELECTED=1
      shift
      ;;
    --address)
      RUN_ADDRESS=1
      CHECK_SELECTED=1
      shift
      ;;
    --airplay)
      RUN_AIRPLAY=1
      CHECK_SELECTED=1
      shift
      ;;
    --host)
      [ "$#" -ge 2 ] || die_usage "--host requires an IPv4 address"
      HOST="$2"
      shift 2
      ;;
    --host=*)
      HOST="${1#*=}"
      shift
      ;;
    --location)
      [ "$#" -ge 2 ] || die_usage "--location requires a URL"
      LOCATION="$2"
      shift 2
      ;;
    --location=*)
      LOCATION="${1#*=}"
      shift
      ;;
    --ssdp-target)
      [ "$#" -ge 2 ] || die_usage "--ssdp-target requires a host"
      SSDP_TARGET="$2"
      shift 2
      ;;
    --ssdp-target=*)
      SSDP_TARGET="${1#*=}"
      shift
      ;;
    --timeout)
      [ "$#" -ge 2 ] || die_usage "--timeout requires a number of seconds"
      TIMEOUT="$2"
      shift 2
      ;;
    --timeout=*)
      TIMEOUT="${1#*=}"
      shift
      ;;
    *)
      die_usage "unknown option '$1'"
      ;;
  esac
done

# No check was named: the owner wants the whole answer.
if [ "$CHECK_SELECTED" -eq 0 ]; then
  RUN_SSDP=1
  RUN_DESCRIPTION=1
  RUN_ADDRESS=1
  RUN_AIRPLAY=1
fi

case "$TIMEOUT" in
  ''|*[!0-9]*) die_usage "--timeout must be a number of seconds, got '$TIMEOUT'" ;;
esac
[ "$TIMEOUT" -ge 1 ] || die_usage "--timeout must be at least 1 second"

if [ -n "$HOST" ]; then
  case "$HOST" in
    *[!0-9.]*) die_usage "--host needs an IPv4 address, got '$HOST'" ;;
  esac
fi

SSDP_HOST="$SSDP_TARGET"
SSDP_PORT=1900
case "$SSDP_TARGET" in
  *:*)
    SSDP_HOST="${SSDP_TARGET%%:*}"
    SSDP_PORT="${SSDP_TARGET##*:}"
    ;;
esac
case "$SSDP_PORT" in
  ''|*[!0-9]*) die_usage "--ssdp-target needs a port number, got '$SSDP_TARGET'" ;;
esac
case "$SSDP_HOST" in
  239.255.255.250|127.0.0.1|localhost)
    ;;
  *)
    die_usage "--ssdp-target accepts only the SSDP multicast group or a loopback address (self-test aid); use --host <ip> to probe a device directly"
    ;;
esac
if [ -n "$LOCATION" ] && [ "${LOCATION#http://}" = "$LOCATION" ]; then
  die_usage "--location must be an http:// URL"
fi

# ── the run ────────────────────────────────────────────────────────────────
dc_init_temp
detect_local_addresses

printf 'PhairPlay network check\n'
printf '  when      : %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
printf '  laptop    : %s\n' "${LOCAL_IP:-unknown}"
if [ -n "$LOCAL_IP_OTHERS" ]; then
  printf '  also      : %s\n' "$LOCAL_IP_OTHERS"
fi
printf '  discovery : %s:%s (M-SEARCH, %s s)\n' "$SSDP_HOST" "$SSDP_PORT" "$TIMEOUT"
if [ -n "$HOST" ]; then
  printf '  device    : %s (--host)\n' "$HOST"
fi

NEEDS_DISCOVERY=0
[ "$RUN_SSDP" = "1" ] && NEEDS_DISCOVERY=1
[ "$RUN_DESCRIPTION" = "1" ] && [ -z "$LOCATION" ] && NEEDS_DISCOVERY=1
[ "$RUN_ADDRESS" = "1" ] && [ -z "$LOCATION" ] && NEEDS_DISCOVERY=1
[ "$RUN_AIRPLAY" = "1" ] && [ -z "$HOST" ] && NEEDS_DISCOVERY=1

if [ "$NEEDS_DISCOVERY" = "1" ]; then
  run_discovery
fi

if [ -n "$LOCATION" ]; then
  # The owner knows the URL (from the log of the television) — it replaces the
  # discovered one, but the address of the device stays the one of the answer.
  DISC_LOCATION="$LOCATION"
fi

resolve_device_address
if [ -n "$DISC_LOCATION" ]; then
  LOCATION_IP="$(url_host "$DISC_LOCATION")"
  LOCATION_PORT="$(url_port "$DISC_LOCATION")"
fi

if [ "$RUN_SSDP" = "1" ]; then
  check_ssdp
fi
if [ "$RUN_DESCRIPTION" = "1" ]; then
  check_description
fi
if [ "$RUN_ADDRESS" = "1" ]; then
  check_address
fi
if [ "$RUN_AIRPLAY" = "1" ]; then
  check_airplay
fi

summary
exit $?
