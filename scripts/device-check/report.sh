#!/usr/bin/env bash
#
# report.sh — the four verdicts and the summary of device-check.sh.
#
# WHY a module of its own: the wording of a verdict is the product of this
#      tool. The owner reads it in a hurry and copies the last line into an
#      issue, so every check has to answer the same three questions: what was
#      measured, what does it mean, what has to happen next.
#
# This file is sourced by scripts/device-check.sh — it must not be executed
# directly.
#

# ── 1 — the discovery ───────────────────────────────────────────────────────
check_ssdp() {
  section "1" "SSDP discovery -- is the renderer visible in the network?"
  if [ "$RES_SSDP" = "ok" ]; then
    verdict "FOUND -- the television answers the discovery"
    field "LOCATION" "$DISC_LOCATION"
    field "SERVER" "$DISC_SERVER"
    field "USN" "$DISC_USN"
    field "ST" "$DISC_ST"
    field "from" "$DISC_SOURCE"
    if [ "$ANSWER_COUNT" -gt 1 ]; then
      field "answers" "$ANSWER_COUNT (the best matching one is shown)"
    fi
    if [ "$DISC_SAMSUNG" = "yes" ]; then
      field "bonus" "a Samsung / multiscreen answer arrived (ST: ${DISC_SAMSUNG_ST}) -- a Samsung TV finds the renderer this way"
    else
      field "bonus" "no Samsung / multiscreen answer -- that is normal unless the television is a Samsung model"
    fi
    meaning "the renderer answers the M-SEARCH, so it is visible in the network of this laptop."
    action "none -- go on with the next check."
    RES_SSDP="ok"
  else
    verdict "NOT FOUND -- no answer arrived"
    if [ -n "$PROBE_ERROR" ]; then
      field "problem" "$PROBE_ERROR"
    fi
    field "searched" "${SSDP_HOST}:${SSDP_PORT} for ${TIMEOUT} s"
    meaning "no renderer answered the discovery, so the television does not see PhairPlay in this network."
    action "check in this order: (1) is the PhairPlay app running on the television? (2) are the laptop and the television in the same Wi-Fi (same SSID, no guest network)? (3) does the router filter multicast (AP isolation, IGMP snooping)? (4) does the app advertise a UUID that differs from the one in the description?"
    RES_SSDP="fail"
  fi
}

# ── 2 — the description document ────────────────────────────────────────────
check_description() {
  section "2" "Description document -- can a control point read it?"
  if [ -z "$DISC_LOCATION" ]; then
    verdict "NOT READABLE -- no description URL is known"
    meaning "without the LOCATION header of the discovery there is no document to read."
    action "run the discovery first (--ssdp) and check the network."
    RES_DESCRIPTION="fail"
    return
  fi
  field "URL" "$DISC_LOCATION"
  resolve_device_address
  if ! is_local_address "$LOCATION_IP"; then
    verdict "NOT FETCHED -- the description URL names a host outside the local network"
    field "URL address" "$LOCATION_IP:$LOCATION_PORT"
    meaning "the URL of the document points somewhere the script must not fetch -- the advertisement of the device is broken."
    action "restart the app on the television; a renderer in the local network advertises a local address."
    RES_DESCRIPTION="fail"
    return
  fi
  if ! fetch_description "$DISC_LOCATION" "$TMP/description.xml"; then
    verdict "NOT READABLE -- the request failed"
    field "error" "$DESC_ERROR"
    meaning "the description URL does not answer -- the network or the server on the television is broken."
    action "restart the app on the television (Home -> Restart); if it stays broken, check whether a firewall blocks the port of the description URL."
    RES_DESCRIPTION="fail"
    return
  fi
  field "HTTP status" "$DESC_STATUS"
  if [ "$DESC_STATUS" != "200" ]; then
    verdict "INCOMPLETE -- the server answered with HTTP $DESC_STATUS instead of 200"
    meaning "a control point stops here: it cannot read the device description."
    action "restart the app on the television; if the status stays wrong, the description server of the app is the problem, not the network."
    RES_DESCRIPTION="fail"
    return
  fi
  if [ "$DESC_HAS_ROOT" -ne 1 ] || [ "$DESC_HAS_DEVICE" -ne 1 ]; then
    verdict "INCOMPLETE -- the answer is not a UPnP device description"
    [ "$DESC_HAS_ROOT" -ne 1 ] && field "missing" "<root>"
    [ "$DESC_HAS_DEVICE" -ne 1 ] && field "missing" "<device>"
    meaning "the file that came back is not a device description, so no control point can use it."
    action "restart the app on the television and run this script again; if it stays broken, attach this output to an issue."
    RES_DESCRIPTION="fail"
    return
  fi
  if [ -n "$DESC_MISSING" ]; then
    verdict "INCOMPLETE -- the description lacks $DESC_MISSING"
    field "services" "${DESC_SERVICES:-none}"
    field "friendlyName" "${DESC_FRIENDLY_NAME:-none}"
    meaning "a control point that needs one of the missing services cannot drive the renderer."
    action "restart the app on the television; the service list belongs to the app, so a missing service is an app problem, not a network problem."
    RES_DESCRIPTION="fail"
    return
  fi
  verdict "COMPLETE -- the description names the device and all three services"
  field "friendlyName" "${DESC_FRIENDLY_NAME:-none}"
  field "services" "$DESC_SERVICES"
  field "UDN" "${DESC_UDN:-none}"
  if [ -n "$DISC_USN" ] && [ -n "$DESC_UDN" ]; then
    local answer_uuid="${DISC_USN%%::*}"
    if [ "$answer_uuid" != "$DESC_UDN" ]; then
      field "warning" "the answer announces ${answer_uuid}, the document announces ${DESC_UDN} -- a control point ignores a device whose UUID does not match"
    fi
  fi
  meaning "the description document is readable and complete, so a control point on this network can drive the renderer."
  action "none -- go on with the next check."
  RES_DESCRIPTION="ok"
}

# ── 3 — the address of the description URL ──────────────────────────────────
#
# WHY this check exists: the renderer used to advertise an address it no longer
#      had. A control point that trusts the LOCATION header then fetches a
#      host that does not exist -- and the owner sees "the TV does not appear"
#      although the network is fine.
check_address() {
  section "3" "Description URL -- does it point at the address the renderer really has?"
  if [ -z "$DISC_LOCATION" ]; then
    verdict "UNKNOWN -- no description URL is known"
    meaning "without a LOCATION header there is no address to compare."
    action "run the discovery first (--ssdp)."
    RES_ADDRESS="fail"
    return
  fi
  resolve_device_address
  field "URL address" "$LOCATION_IP:$LOCATION_PORT"
  if [ -n "$LOCAL_IP" ]; then
    field "this laptop" "$LOCAL_IP"
  fi
  if [ -z "$EXPECTED_IP" ]; then
    verdict "UNKNOWN -- the address of the device is not known"
    meaning "the script cannot tell whether the description URL points at the right address."
    action "run the script again with --host <the IP address of the television>."
    RES_ADDRESS="fail"
    return
  fi
  field "device address" "$EXPECTED_IP ($EXPECTED_SOURCE)"
  if [ "$LOCATION_IP" = "$EXPECTED_IP" ]; then
    verdict "MATCH -- the description URL points at the address of the device"
    meaning "the renderer advertises the address it really has, so a control point fetches the description from the right host."
    action "none -- go on with the next check."
    RES_ADDRESS="ok"
    return
  fi
  verdict "MISMATCH -- the description URL points at another address"
  meaning "the renderer answers from ${EXPECTED_IP} but advertises ${LOCATION_IP}. A control point that trusts the LOCATION header fetches a host that does not exist any more -- the symptom of an address that survived a change of the network."
  action "restart the PhairPlay app on the television (Home -> Restart) so that it advertises its current address, then run this script again."
  RES_ADDRESS="fail"
}

# ── 4 — the AirPlay port ────────────────────────────────────────────────────
check_airplay() {
  section "4" "AirPlay port 7000 -- can a sender reach the receiver?"
  resolve_device_address
  if [ -z "$EXPECTED_IP" ]; then
    verdict "UNKNOWN -- the address of the device is not known"
    meaning "without an address the AirPlay port cannot be checked."
    action "run the script again with --host <the IP address of the television>."
    RES_AIRPLAY="fail"
    return
  fi
  field "target" "$EXPECTED_IP:7000"
  field "probe" "bash /dev/tcp"
  if tcp_probe "$EXPECTED_IP" 7000; then
    verdict "REACHABLE -- the port accepts a connection"
    meaning "a sender on this network can open the AirPlay connection on the television."
    action "none -- go on with the next check."
    RES_AIRPLAY="ok"
  else
    verdict "NOT REACHABLE -- no connection possible"
    meaning "the renderer is visible, but nothing answers on the AirPlay port, so a sender cannot stream to the television."
    action "restart the app on the television; if the port stays closed, the AirPlay receiver did not start (adb logcat -s PhairPlay)."
    RES_AIRPLAY="fail"
  fi
}

# ── the summary ─────────────────────────────────────────────────────────────
#
# WHY the phrases: the summary is the line the owner copies into an issue. It
#      has to name the checks that ran -- a summary that claims a complete
#      description after a single discovery would be a lie.
join_phrases() {
  local list="$1" result="" index=0 total item
  local IFS=$'\n'
  set -- $list
  total=$#
  for item in "$@"; do
    [ -n "$item" ] || continue
    index=$((index + 1))
    if [ "$index" -eq 1 ]; then
      result="$item"
    elif [ "$index" -eq "$total" ]; then
      result="$result and $item"
    else
      result="$result, $item"
    fi
  done
  printf '%s' "$result"
}

summary() {
  local state="OK" text next good="" bad=""
  count_results
  [ "$RES_SSDP" = "ok" ] && good="${good}the renderer is visible in the network
"
  [ "$RES_DESCRIPTION" = "ok" ] && good="${good}its description is complete
"
  [ "$RES_ADDRESS" = "ok" ] && good="${good}the description URL points at the right address
"
  [ "$RES_AIRPLAY" = "ok" ] && good="${good}the AirPlay port is open
"
  [ "$RES_SSDP" = "fail" ] && bad="${bad}the renderer did not answer the discovery
"
  [ "$RES_DESCRIPTION" = "fail" ] && bad="${bad}the description document is not readable
"
  [ "$RES_ADDRESS" = "fail" ] && bad="${bad}the description URL points at another address
"
  [ "$RES_AIRPLAY" = "fail" ] && bad="${bad}the AirPlay port is not reachable
"
  if [ "$CHECKS_FAILED" -eq 0 ]; then
    state="OK"
  elif [ "$RES_SSDP" = "fail" ] && [ "$CHECKS_OK" -eq 0 ]; then
    state="UNREACHABLE"
  else
    state="PARTIAL"
  fi
  case "$state" in
    OK)
      text="$(join_phrases "$good")."
      next="let the television search for the renderer again (case DT-09 of the device test plan) and play a file."
      ;;
    UNREACHABLE)
      text="the renderer did not answer at all -- the television does not see PhairPlay in this network."
      next="start PhairPlay on the television, then check the Wi-Fi of the laptop (same SSID, no client isolation); see docs/guides/NETWORK_CHECK.md."
      ;;
    *)
      local good_text bad_text
      good_text="$(join_phrases "$good")"
      bad_text="$(join_phrases "$bad")"
      if [ -n "$good_text" ]; then
        text="$good_text, but $bad_text."
      else
        text="$bad_text."
      fi
      next=""
      if [ "$RES_ADDRESS" = "fail" ]; then
        next="restart the PhairPlay app on the television (Home -> Restart) so that it advertises its current address, then run this script again."
      elif [ "$RES_DESCRIPTION" = "fail" ]; then
        next="restart the PhairPlay app on the television (Home -> Restart) and run this script again."
      elif [ "$RES_AIRPLAY" = "fail" ]; then
        next="restart the PhairPlay app on the television and check the log (adb logcat -s PhairPlay)."
      fi
      ;;
  esac
  printf '\nSUMMARY: %s -- %s\n' "$state" "$text"
  printf 'NEXT ACTION: %s\n' "$next"
  printf 'Copy the summary above into the issue.\n'
  case "$state" in
    OK) return 0 ;;
    UNREACHABLE) return 2 ;;
    *) return 1 ;;
  esac
}
