#!/usr/bin/env bash
#
# description.sh — the device description document of device-check.sh.
#
# WHY a module of its own: the description is the second half of the tool. It
#      is fetched over HTTP and parsed without a XML parser, and both steps
#      have their own failure modes — a wrong status, a document that is not a
#      device description, a missing service.
#
# This file is sourced by scripts/device-check.sh — it must not be executed
# directly.
#

# ── the parser ──────────────────────────────────────────────────────────────
#
# WHY no xmllint: xmllint is not installed everywhere, and the document is
#      small and flat. grep and sed read the three things that matter — the
#      name of the device, the list of the services and the identity of the
#      device.
#
# WHY the first name and not the last one: a UPnP description may carry a
#      friendlyName in the service elements as well. The name of the device is
#      the first one in the document — that is the name the control point
#      shows in its picker.
parse_description() {
  local body="$1" flat service
  flat="$(tr '\n' ' ' <"$body")"
  DESC_FRIENDLY_NAME="$(printf '%s' "$flat" |
    grep -o '<friendlyName[^>]*>[^<]*</friendlyName>' | head -1 |
    sed 's/.*>\([^<]*\)<.*/\1/' |
    sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  DESC_UDN="$(printf '%s' "$flat" |
    grep -o '<UDN[^>]*>[^<]*</UDN>' | head -1 |
    sed 's/.*>\([^<]*\)<.*/\1/' |
    sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  DESC_HAS_ROOT=0
  DESC_HAS_DEVICE=0
  case "$flat" in
    *"<root"*) DESC_HAS_ROOT=1 ;;
  esac
  case "$flat" in
    *"<device"*) DESC_HAS_DEVICE=1 ;;
  esac
  DESC_SERVICES=""
  DESC_MISSING=""
  for service in AVTransport RenderingControl ConnectionManager; do
    if printf '%s' "$flat" | grep -q "<serviceType>[^<]*${service}"; then
      DESC_SERVICES="$DESC_SERVICES ${service}"
    else
      DESC_MISSING="$DESC_MISSING ${service}"
    fi
  done
  DESC_SERVICES="${DESC_SERVICES# }"
  DESC_MISSING="${DESC_MISSING# }"
}

# ── the fetch ───────────────────────────────────────────────────────────────
#
# WHY a separate file for the body: the answer has to be written to a file
#      before it can be parsed, and a pipe cannot carry the payload and the
#      status code of the same request.
#
# WHY --max-time: the script must not hang when a device accepts the
#      connection and then stays silent. The same timeout that bounds the
#      discovery bounds the fetch.
#
# WHY --max-filesize: a device description is about three kilobytes. A device
#      that answers with megabytes must not be able to fill the memory of the
#      laptop, so the download is bounded.
fetch_description() {
  local url="$1" body="$2" status="" curl_code=0
  DESC_STATUS=""
  DESC_ERROR=""
  status="$(curl -sS --connect-timeout "$TIMEOUT" --max-time "$TIMEOUT" \
    --max-filesize 1048576 -o "$body" -w '%{http_code}' "$url" 2>"$TMP/curl.err")"
  curl_code=$?
  if [ "$curl_code" -ne 0 ]; then
    DESC_STATUS="000"
    DESC_ERROR="$(head -1 "$TMP/curl.err" 2>/dev/null | tr '\n' ' ')"
    return 1
  fi
  DESC_STATUS="$status"
  parse_description "$body"
  return 0
}
