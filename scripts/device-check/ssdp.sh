#!/usr/bin/env bash
#
# ssdp.sh — the SSDP discovery of device-check.sh.
#
# WHY a module of its own: the discovery is the only part of the tool that
#      speaks a binary protocol. Everything else reads text. Keeping the
#      socket handling here means the rest of the tool can stay plain shell.
#
# This file is sourced by scripts/device-check.sh — it must not be executed
# directly.
#

# ── the probe ───────────────────────────────────────────────────────────────
#
# WHY python3: a UDP socket that is connected to the multicast group throws
#      away every answer that comes from the address of the renderer itself,
#      and bash cannot read a datagram without a helper. python3 can, so it
#      does the listening; the script itself stays plain bash.
#
# WHY three search targets: a renderer that answers only "ssdp:all" and one
#      that answers only the MediaRenderer target both exist, and a Samsung
#      television answers a third, vendor specific target. All three are
#      asked, so one run sees every device that can be seen.
ssdp_probe_python() {
  python3 - "$SSDP_HOST" "$SSDP_PORT" "$TIMEOUT" "$1" <<'PY' 2>"$TMP/probe.err"
import socket, struct, sys, time

host, port, timeout, out = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
SEARCH_TARGETS = ["urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all",
                  "urn:samsung.com:service:MultiScreenService:1"]

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
# The own M-SEARCH must not come back as an answer — it would only cost time.
sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 0)
sock.settimeout(0.5)

# Joining the group is a bonus, not a requirement: a renderer answers a
# M-SEARCH unicast to the sender, so the probe works without the right to
# join a multicast group. If the join fails, the probe continues.
try:
    membership = struct.pack("4sl", socket.inet_aton(host), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
except OSError:
    pass

for target in SEARCH_TARGETS:
    message = ("M-SEARCH * HTTP/1.1\r\n"
               "HOST: {0}:{1}\r\n"
               'MAN: "ssdp:discover"\r\n'
               "MX: 2\r\n"
               "ST: {2}\r\n\r\n").format(host, port, target)
    try:
        sock.sendto(message.encode("utf-8"), (host, port))
    except OSError as error:
        sys.stderr.write("M-SEARCH could not be sent: {0}\n".format(error))
        sys.exit(3)

deadline = time.time() + timeout
with open(out, "w") as handle:
    while time.time() < deadline:
        try:
            data, address = sock.recvfrom(8192)
        except socket.timeout:
            continue
        except OSError:
            break
        text = data.decode("utf-8", "replace")
        if not text.upper().startswith("HTTP/1.1 200"):
            continue
        handle.write("=== ANSWER {0}:{1}\n".format(address[0], address[1]))
        handle.write(text.replace("\r\n", "\n"))
        handle.write("\n")
PY
}

ssdp_probe() {
  local out="$1"
  : >"$out"
  if ! command -v python3 >/dev/null 2>&1; then
    PROBE_ERROR="python3 is not installed, so no M-SEARCH can be sent (macOS: xcode-select --install)"
    return 1
  fi
  if ssdp_probe_python "$out"; then
    return 0
  fi
  PROBE_ERROR="$(head -1 "$TMP/probe.err" 2>/dev/null)"
  [ -n "$PROBE_ERROR" ] || PROBE_ERROR="the M-SEARCH could not be sent"
  return 1
}

# ── the answers of the probe ───────────────────────────────────────────────
#
# WHY an own parser: the answers are plain text, but the interesting part is
#      the header block of each answer, and the blocks have to be separated
#      from each other. awk does that without a helper program.
collect_answers() {
  local file="$1"
  awk '
    /^=== ANSWER / {
      if (source != "") print source "\037" loc "\037" srv "\037" usn "\037" st
      source = $3; loc = ""; srv = ""; usn = ""; st = ""; next
    }
    /^LOCATION:/ { loc = $0; sub(/^[^:]*:[ \t]*/, "", loc) }
    /^SERVER:/   { srv = $0; sub(/^[^:]*:[ \t]*/, "", srv) }
    /^USN:/      { usn = $0; sub(/^[^:]*:[ \t]*/, "", usn) }
    /^ST:/       { st  = $0; sub(/^[^:]*:[ \t]*/, "", st) }
    END { if (source != "") print source "\037" loc "\037" srv "\037" usn "\037" st }
  ' "$file" >"$TMP/answers.tsv" 2>/dev/null || true
}

# WHY the scoring: a network can hold several UPnP devices. The answer of the
#      PhairPlay renderer is the interesting one, and a MediaRenderer answer
#      is better than the answer of a root device.
select_answer() {
  local best_score=0 source loc srv usn st score
  while IFS="$SEP" read -r source loc srv usn st; do
    ANSWER_COUNT=$((ANSWER_COUNT + 1))
    case "$st$srv$usn" in
      *Samsung*|*samsung*|*MultiScreen*|*MULTISCREEN*)
        DISC_SAMSUNG="yes"
        [ -n "$DISC_SAMSUNG_ST" ] || DISC_SAMSUNG_ST="$st"
        ;;
    esac
    [ -n "$loc" ] || continue
    score=1
    case "$st" in
      "$MEDIA_RENDERER_ST") score=2 ;;
    esac
    case "$srv$usn" in
      *PhairPlay*|*phairplay*) score=$((score + 2)) ;;
    esac
    if [ "$score" -gt "$best_score" ]; then
      best_score="$score"
      DISC_LOCATION="$loc"
      DISC_SERVER="$srv"
      DISC_USN="$usn"
      DISC_ST="$st"
      DISC_SOURCE="$source"
    fi
  done <"$TMP/answers.tsv"
}

run_discovery() {
  if ! ssdp_probe "$TMP/answers.raw"; then
    RES_SSDP="fail"
    return 1
  fi
  collect_answers "$TMP/answers.raw"
  select_answer
  if [ -z "$DISC_LOCATION" ]; then
    RES_SSDP="fail"
    return 1
  fi
  RES_SSDP="ok"
  return 0
}

# The address the device under test really has: the one the owner names, or the
# one the renderer answered from. That address is the reference for the
# comparison in check_address — it is the address the renderer really has, and
# the LOCATION header is the address the renderer claims to have.
resolve_device_address() {
  EXPECTED_IP="$HOST"
  EXPECTED_SOURCE=""
  if [ -n "$EXPECTED_IP" ]; then
    EXPECTED_SOURCE="the address given with --host"
    return
  fi
  if [ -n "$DISC_SOURCE" ]; then
    EXPECTED_IP="${DISC_SOURCE%%:*}"
    EXPECTED_SOURCE="the address the renderer answered from"
  fi
}
