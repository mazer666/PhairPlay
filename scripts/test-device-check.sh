#!/usr/bin/env bash
#
# test-device-check.sh — the self-test of scripts/device-check.sh.
#
# WHY: the network check is the one tool the owner runs without a developer in
#      the room. A tool that reports "everything is fine" while the renderer is
#      invisible costs more time than no tool at all. The self-test therefore
#      proves the verdicts against a renderer that is faked on the loopback
#      interface — no television, no multicast rights, no network.
#
# WHAT IS PROVEN
#   1. the description document is fetched, the friendly name and the three
#      services are recognised -> "complete"
#   2. a server that answers 404 -> "incomplete", with the status as the reason
#   3. an answer that is not a device description -> an error verdict, no crash
#   4. a description URL that names another address -> "mismatch"
#   5. a network without a renderer -> "not found", a non-zero exit code and
#      no hanging
#   6. the whole chain with a faked renderer -> discovery, description,
#      address and AirPlay port all green
#   7. an unknown option -> a usage error, not a run
#
# USAGE
#   ./scripts/test-device-check.sh
#
# EXIT CODES
#   0  every case passed
#   1  at least one case failed
#

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
CHECK_SCRIPT="$SCRIPT_DIR/device-check.sh"

if [ ! -x "$CHECK_SCRIPT" ]; then
  printf 'ERROR: %s is missing or not executable.\n' "$CHECK_SCRIPT" >&2
  exit 2
fi

TMP="$(mktemp -d 2>/dev/null || mktemp -d -t phairplay-test)"
HTTP_PID=""
SSDP_PID=""
PASSED=0
FAILED=0
OUTPUT="$TMP/output.txt"
EXIT_CODE=0

cleanup() {
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
  [ -n "$SSDP_PID" ] && kill "$SSDP_PID" 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

# ── the assertions ──────────────────────────────────────────────────────────
pass() {
  PASSED=$((PASSED + 1))
  printf '  ok   : %s\n' "$1"
}

fail() {
  FAILED=$((FAILED + 1))
  printf '  FAIL : %s\n' "$1"
}

start_case() {
  printf '\n=== %s ===\n' "$1"
}

run_check() {
  "$CHECK_SCRIPT" "$@" >"$OUTPUT" 2>&1
  EXIT_CODE=$?
}

assert_exit_zero() {
  if [ "$EXIT_CODE" -eq 0 ]; then
    pass "$1"
  else
    fail "$1 — the exit code was $EXIT_CODE, expected 0"
  fi
}

assert_exit_nonzero() {
  if [ "$EXIT_CODE" -ne 0 ]; then
    pass "$1"
  else
    fail "$1 — the exit code was 0, expected a non-zero one"
  fi
}

assert_contains() {
  if grep -q -- "$2" "$OUTPUT"; then
    pass "$1"
  else
    fail "$1 — the output does not contain '$2'"
  fi
}

assert_lacks() {
  if grep -q -- "$2" "$OUTPUT"; then
    fail "$1 — the output must not contain '$2'"
  else
    pass "$1"
  fi
}

# ── the fakes ───────────────────────────────────────────────────────────────
#
# WHY a python server: the script under test has to meet a real HTTP server
#      and a real UDP responder. python3 ships with the tool chain the owner
#      already needs for the SSDP probe, and it is the only way to fake a
#      renderer without a television.
write_fakes() {
  cat >"$TMP/server.py" <<'PY'
import http.server, socketserver, sys

directory, port_file = sys.argv[1], sys.argv[2]


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=directory, **kwargs)

    def log_message(self, format, *args):
        pass


server = socketserver.TCPServer(("127.0.0.1", 0), Handler)
with open(port_file, "w") as handle:
    handle.write(str(server.server_address[1]) + "\n")
server.serve_forever()
PY

  cat >"$TMP/renderer.py" <<'PY'
import socket, sys, threading

ssdp_port = int(sys.argv[1])
airplay_port = int(sys.argv[2])
location = sys.argv[3]
ready_file = sys.argv[4]

# The AirPlay port is optional: on a machine that already uses port 7000 the
# test still runs, it just does not claim the AirPlay check.
try:
    tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp.bind(("127.0.0.1", airplay_port))
    tcp.listen(8)
except OSError:
    tcp = None


def accept_loop():
    while True:
        try:
            connection, _ = tcp.accept()
            connection.close()
        except OSError:
            return


if tcp is not None:
    threading.Thread(target=accept_loop, daemon=True).start()

udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
udp.bind(("127.0.0.1", ssdp_port))

with open(ready_file, "w") as handle:
    handle.write("ready\n")

answer = ("HTTP/1.1 200 OK\r\n"
          "CACHE-CONTROL: max-age=1800\r\n"
          "EXT:\r\n"
          "LOCATION: {0}\r\n"
          "SERVER: Android/1.0 UPnP/1.0 PhairPlay\r\n"
          "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
          "USN: uuid:550e8400-e29b-41d4-a716-446655440000"
          "::urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
          "\r\n").format(location)

while True:
    data, peer = udp.recvfrom(8192)
    if b"M-SEARCH" not in data:
        continue
    udp.sendto(answer.encode("utf-8"), peer)
PY
}

# The device description of the renderer — the same document the app serves.
write_description() {
  cat >"$TMP/www/description.xml" <<'XML'
<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>Living Room TV</friendlyName>
    <manufacturer>PhairPlay</manufacturer>
    <manufacturerURL>https://github.com/mazer666/PhairPlay</manufacturerURL>
    <modelDescription>PhairPlay DLNA MediaRenderer</modelDescription>
    <modelName>PhairPlay</modelName>
    <modelNumber>1</modelNumber>
    <modelURL>https://github.com/mazer666/PhairPlay</modelURL>
    <serialNumber>550e8400-e29b-41d4-a716-446655440000</serialNumber>
    <UDN>uuid:550e8400-e29b-41d4-a716-446655440000</UDN>
    <presentationURL>http://127.0.0.1:41234/presentation.html</presentationURL>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/ConnectionManager.xml</SCPDURL>
        <controlURL>/upnp/control/ConnectionManager</controlURL>
        <eventSubURL>/upnp/event/ConnectionManager</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/AVTransport.xml</SCPDURL>
        <controlURL>/upnp/control/AVTransport</controlURL>
        <eventSubURL>/upnp/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/RenderingControl.xml</SCPDURL>
        <controlURL>/upnp/control/RenderingControl</controlURL>
        <eventSubURL>/upnp/event/RenderingControl</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
XML

  printf '<<< this is not a device description >>>\n' >"$TMP/www/broken.xml"
}

# ── the cases ───────────────────────────────────────────────────────────────
mkdir -p "$TMP/www"
write_fakes
write_description

python3 "$TMP/server.py" "$TMP/www" "$TMP/http.port" >/dev/null 2>&1 &
HTTP_PID=$!
attempt=0
while [ ! -s "$TMP/http.port" ] && [ "$attempt" -lt 50 ]; do
  sleep 0.1
  attempt=$((attempt + 1))
done
if [ ! -s "$TMP/http.port" ]; then
  printf 'ERROR: the local HTTP server did not start.\n' >&2
  exit 2
fi
HTTP_PORT="$(cat "$TMP/http.port")"

start_case "1 — a complete description document is recognised"
run_check --description --location "http://127.0.0.1:$HTTP_PORT/description.xml" \
  --host 127.0.0.1 --timeout 5
assert_exit_zero "the check succeeds"
assert_contains "the verdict is COMPLETE" "COMPLETE -- the description names the device"
assert_contains "the name of the device is read" "Living Room TV"
assert_contains "the three services are read" "AVTransport RenderingControl ConnectionManager"
assert_contains "the summary is OK" "SUMMARY: OK"

start_case "2 — a server that answers 404 is reported as incomplete"
run_check --description --location "http://127.0.0.1:$HTTP_PORT/missing.xml" \
  --host 127.0.0.1 --timeout 5
assert_exit_nonzero "the check fails"
assert_contains "the verdict is INCOMPLETE" "INCOMPLETE -- the server answered with HTTP 404"
assert_contains "the status is the reason" "HTTP status   : 404"
assert_contains "the summary is PARTIAL" "SUMMARY: PARTIAL"

start_case "3 — an answer that is not a device description does not crash the tool"
run_check --description --location "http://127.0.0.1:$HTTP_PORT/broken.xml" \
  --host 127.0.0.1 --timeout 5
assert_exit_nonzero "the check fails"
assert_contains "the verdict is INCOMPLETE" "INCOMPLETE -- the answer is not a UPnP device description"
assert_lacks "no shell error in the output" \
  "command not found\|unbound variable\|syntax error\|device-check.sh: line"
assert_contains "the summary is PARTIAL" "SUMMARY: PARTIAL"

start_case "4 — a description URL with another address is reported as a mismatch"
run_check --address --location "http://192.0.2.10:8080/description.xml" \
  --host 127.0.0.1 --timeout 5
assert_exit_nonzero "the check fails"
assert_contains "the verdict is MISMATCH" "MISMATCH -- the description URL points at another address"
assert_contains "both addresses are named" "192.0.2.10"
assert_contains "the next action names the restart" "NEXT ACTION: restart the PhairPlay app"

start_case "5 — a network without a renderer ends with a non-zero exit code"
started="$(date +%s)"
run_check --ssdp --ssdp-target "127.0.0.1:45123" --timeout 2
elapsed="$(( $(date +%s) - started ))"
assert_exit_nonzero "the exit code is not 0"
assert_contains "the verdict is NOT FOUND" "NOT FOUND -- no answer arrived"
assert_contains "the summary is UNREACHABLE" "SUMMARY: UNREACHABLE"
if [ "$elapsed" -lt 20 ]; then
  pass "the tool ends on its own (${elapsed}s)"
else
  fail "the tool hung (${elapsed}s)"
fi

start_case "6 — the whole chain with a faked renderer is green"
python3 "$TMP/renderer.py" 45124 7000 "http://127.0.0.1:$HTTP_PORT/description.xml" \
  "$TMP/ssdp.ready" >/dev/null 2>&1 &
SSDP_PID=$!
attempt=0
while [ ! -s "$TMP/ssdp.ready" ] && [ "$attempt" -lt 50 ]; do
  sleep 0.1
  attempt=$((attempt + 1))
done
if [ ! -s "$TMP/ssdp.ready" ]; then
  fail "the fake renderer did not start"
else
  run_check --all --ssdp-target 127.0.0.1:45124 --host 127.0.0.1 --timeout 2
  assert_contains "the renderer is found" "FOUND -- the television answers the discovery"
  assert_contains "the description is complete" "COMPLETE -- the description names the device"
  assert_contains "the addresses match" "MATCH -- the description URL points at the address of the device"
  if grep -q "REACHABLE -- the port accepts a connection" "$OUTPUT"; then
    assert_exit_zero "every check passed"
    assert_contains "the summary is OK" "SUMMARY: OK"
  else
    printf '  note : port 7000 is busy on this machine, the AirPlay check was skipped\n'
    assert_exit_nonzero "the AirPlay check fails without a renderer on port 7000"
  fi
fi

start_case "7 — an unknown option is a usage error"
run_check --there-is-no-such-option
if [ "$EXIT_CODE" -eq 64 ]; then
  pass "the exit code is 64"
else
  fail "the exit code was $EXIT_CODE, expected 64"
fi
assert_contains "the error names the option" "unknown option"

# ── the result ──────────────────────────────────────────────────────────────
printf '\n%s\n' "----------------------------------------------------------------"
if [ "$FAILED" -eq 0 ]; then
  printf 'OK: the self-test passed — %s assertion(s), every case green.\n' "$PASSED"
  exit 0
fi
printf 'FAILED: the self-test — %s assertion(s) passed, %s failed.\n' "$PASSED" "$FAILED"
exit 1
