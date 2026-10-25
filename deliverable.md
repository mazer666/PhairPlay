# M13 „Reconnect“ — Übergabe an den Teamleiter

**Stand:** Branch `main` auf `e91d0fa` (Arbeitsverzeichnis `/workspace/work/m13-reconnect`)
**Ziel:** Nach einem Netzwerkverlust ist **jede** Anzeige wieder da und zeigt auf die
aktuelle Adresse. Damit wird die offene Abweichung **FR-61** geschlossen
(`docs/spec/REQUIREMENTS.md` Z. 197).

---

## 1. Was geändert wurde

| Datei | Zweck | Zeilen |
|---|---|---|
| `app/src/main/kotlin/com/phairplay/dlna/DlnaReadvertisePlan.kt` | **neu.** Reine, JVM-testbare Entscheidung: „nur neu announces“ vs. „Description-Server neu bauen“. Enthält die Adressregeln. | 83 (neu) |
| `app/src/main/kotlin/com/phairplay/dlna/SsdpAdvertiser.kt` | `readvertise()` (Alive-Announcements erneut senden, Socket bei Interface-Verlust neu öffnen), `updateLocation()` (neue Description-URL). Der Listener holt den Socket jetzt pro Runde neu und wartet bei Socket-Wechsel, statt auf einem geschlossenen Socket zu kreisen. | 149 → 295 |
| `app/src/main/kotlin/com/phairplay/dlna/DlnaReceiver.kt` | `readvertise()`: ermittelt die aktuelle IP, lässt die Entscheidung treffen und baut bei geänderter IP den Description-Server neu (neue URL, neuer Port). `start()`/`stop()`-Verhalten unverändert. | 241 → 313 |
| `app/src/main/kotlin/com/phairplay/service/ReceiverSupervisor.kt` | `readvertiseAll()`: AirPlay **und** DLNA. `readvertiseAirPlay()` bleibt unverändert erhalten. | 281 → 299 |
| `app/src/main/kotlin/com/phairplay/service/NetworkRecoveryMonitor.kt` | Nur Text/KDoc: der Rückruf ist jetzt `readvertiseAll()`. **Die Logik „erstes Netzwerk ignorieren / nur nach echtem Verlust“ ist unverändert** (KDoc ab Zeile 61 dokumentiert den Bug). | 128 → 133 |
| `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt` | Eine Zeile: `onNetworkRestored = { receivers.readvertiseAll() }`. | 289 → 289 |
| `app/src/test/kotlin/com/phairplay/dlna/DlnaReadvertisePlanTest.kt` | **neu.** 7 JVM-Tests (siehe Abschnitt 3). | 174 (neu) |

Nicht angefasst (bewusst, laut Auftrag): Manifest, Layouts, Strings, `docs/`.
Die Doku-Stelle „FR-61-Abweichung“ wird vom parallelen Track aktualisiert.

---

## 2. Die Entscheidungslogik

Die Entscheidung liegt in `DlnaReadvertisePlan` (`app/src/main/kotlin/com/phairplay/dlna/DlnaReadvertisePlan.kt:50`).
Sie ist eine **reine Funktion** ohne Android- und ohne Socket-Abhängigkeit, damit sie
im `test-runner`-Modul testbar ist. Der Socket-Teil ist es nicht.

### Die Regel

```
decide(previousAddress, currentAddress) ->
    current = approveAddress(currentAddress)
    wenn current == null              -> ANNOUNCE_ONLY
    wenn previous == current          -> ANNOUNCE_ONLY
    sonst                             -> REBUILD_DESCRIPTION
```

* **ANNOUNCE_ONLY** – die Adresse ist unverändert oder unbrauchbar. Es werden nur die
  Alive-Announcements wiederholt; der laufende Description-Server bleibt stehen.
* **REBUILD_DESCRIPTION** – die Adresse hat sich geändert. Der Description-Server wird
  neu gebaut: **neue URL, neuer Port**. Der Advertiser bekommt die neue URL
  (`SsdpAdvertiser.updateLocation()`), damit auch die `M-SEARCH`-Antworten den neuen
  Stand nennen. Der alte Server wird erst gestoppt, nachdem der neue läuft.

### Wann wird neu gebaut?

**Neu gebaut wird genau dann, wenn die aktuelle Adresse eine *brauchbare* IPv4-Literal
ist und sich von der bisher angekündigten Adresse unterscheidet.**

Als *brauchbar* (`approveAddress()`, Zeile 69) gilt eine Adresse nur, wenn sie:

1. eine reine dotted-quad-Literal ist — vier Dezimal-Oktette 0–255, **ohne** führende
   Null (die Oktal-Schreibweise `0177.0.0.1` wird abgelehnt), und **ohne** Dezimal- oder
   Hex-Schreibweise (`3232235777`). Die Syntax-Regeln werden nicht doppelt erfunden, sie
   kommen aus `GenaCallbackPolicy.parseIpv4Literal()`;
2. nicht `0.0.0.0` ist (unspecified / „this network“);
3. nicht im Loopback `127.0.0.0/8` liegt;
4. nicht Multicast (`224.0.0.0/4`) oder reserviert (`240.0.0.0/4`, enthält Broadcast) ist.

Private Bereiche (10/8, 172.16/12, 192.168/16) und Link-Local (169.254/16) sind
ausdrücklich **erlaubt** — sie sind im lokalen Netz erreichbar.

### Sonderfälle (bewusst entschieden)

| Situation | Entscheidung | Begründung |
|---|---|---|
| Adresse leer / `0.0.0.0` / Loopback-Fallback | ANNOUNCE_ONLY | Das Interface kommt vermutlich erst hoch. Eine neue URL mit kaputter Adresse wäre schlechter als die alte: der Control Point findet den Renderer und wartet dann vergeblich auf die Description. |
| Vorher nie eine Adresse angekündigt (`null`) oder nur der Loopback-Fallback | REBUILD_DESCRIPTION | Der Description-Server zeigt noch auf `127.0.0.1` — er muss auf die echte Adresse gebaut werden. |
| Gleiche IP | ANNOUNCE_ONLY | Die Description stimmt noch; ein Neubau würde nur den Port ändern und laufende Abos zerreißen. |

---

## 3. Die neuen Tests

Datei: `app/src/test/kotlin/com/phairplay/dlna/DlnaReadvertisePlanTest.kt`
(läuft im `test-runner`-Modul, JVM, ohne Android-API)

| # | Testname | Was er beweist |
|---|---|---|
| 1 | `the same address only repeats the announcements` | Gleiche IP → `ANNOUNCE_ONLY`, also **kein** Server-Neubau. |
| 2 | `a changed address rebuilds the description server` | Geänderte IP → `REBUILD_DESCRIPTION`. |
| 3 | `a rebuilt description server carries the new address in its URL` | Der neu gebaute Server liefert eine **andere URL mit der neuen IP** (`192.168.1.6`), die alte Adresse kommt nicht mehr vor, der Port ist neu. Benutzt einen echten `DlnaDescriptionServer`. |
| 4 | `an empty address needs no rebuild` | Leerzeichen/leer/`null` → `ANNOUNCE_ONLY`, **kein Absturz**. |
| 5 | `the unspecified address needs no rebuild` | `0.0.0.0` → `ANNOUNCE_ONLY` und `approveAddress()` liefert `null`. |
| 6 | `a broken address never reaches a description URL` | **Negativtest.** 10 kaputte Adressen (Oktett außerhalb 0–255, zu wenige/zu viele Oktette, kein Literal, Literal mit Anhang, Oktal-/Dezimal-Notation, Loopback, Multicast, Broadcast) werden abgelehnt und lösen **keinen** Rebuild aus — eine kaputte Adresse kann so nicht in eine Description-URL wandern. |
| 7 | `an address that was never advertised is built` | Vorher `null` oder nur Loopback → `REBUILD_DESCRIPTION`, der Description-Server wird also gebaut. |

Zusätzlich geprüft (ohne neuen Test): `bash scripts/check-kdoc-headers.sh --skip-nested`
→ `OK: KDoc header check passed — 210 class declaration(s) checked`.

---

## 4. Was ohne Gerätetest **unbewiesen** bleibt

Ehrlicher Status — diese Teile sind **nicht** durch Tests abgedeckt:

1. **Socket-Verhalten ohne echten Interface-Verlust.** Dass `MulticastSocket` nach dem
   Wegfallen des Interfaces wirklich „Network is unreachable“ wirft und die
   Gruppenmitgliedschaft mit dem Interface stirbt, ist im Sandbox-CI **nicht** beweisbar.
   Die Heuristik (`isSocketHealthy()`: Gruppe neu beitreten; `sendAlive()` schlägt fehl →
   Socket ersetzen → erneut senden) ist plausibel, aber ungetestet.
2. **Echter Netzwerkwechsel.** Wi-Fi aus/an, Standby, VPN up/down, neuer DHCP-Lease:
   ob der Renderer danach in einer echten Samsung-/Windows-/VLC-Control-Point-Liste
   wieder auftaucht, ist **nicht gemessen**. Der Plan ist die Grundlage dafür, aber
   kein Beweis.
3. **Neue URL im echten Betrieb.** Dass ein Control Point die neue Description-URL
   tatsächlich abruft (und nicht die alte aus einem Cache benutzt), ist offen. Der
   CACHE-CONTROL-Header (`max-age=1800`) ist unverändert — ein Control Point, das die
   alte URL cached, wird sie unter Umständen noch Minuten benutzen.
4. **Verhalten bei laufender Wiedergabe.** Der Description-Server-Neubau stoppt den
   alten Server samt GENA-Abonnements. Ob ein Control Point danach korrekt neu abonniert,
   ist **nicht** geprüft.
5. **Kein Build/Testlauf in dieser Sandbox.** Es ist kein JDK installiert und laut
   Auftrag wurde **kein** Gradle-Befehl ausgeführt.

### Testlauf nicht ausgeführt — muss beim Teamleiter verifiziert werden

Auszuführen:

```bash
# 1) JVM-Protokoll-Suite (enthält die neuen Tests)
cd test-runner && gradle test

# 2) Voller App-Build + Unit-Tests
./gradlew :app:testDebugUnitTest

# 3) KDoc-Check
bash scripts/check-kdoc-headers.sh --skip-nested
```

Erwartet: `DlnaReadvertisePlanTest` — 7 Tests, alle grün.
