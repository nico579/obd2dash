# OBD2 Dash

[FR — Français](README.md) | [**EN — English**](README.en.md)

**Stable version: [0.17](https://github.com/nico579/obd2dash/releases/tag/v0.17)**
(`versionCode = 17`) · **Android 8.0 or later** (API 26).
This README describes version 0.17 and the repository as of October 9, 2026.

An Android application (Kotlin, Jetpack Compose) that connects to an ELM327
Wi-Fi, Classic Bluetooth or BLE adapter and reads live OBD2 vehicle data.

This is a personal project, distributed as APKs rather than through the Play Store.
Install an APK first, then use the application's update menu for later versions.
The common interface discovers and reads the standard SAE PIDs available on the
connected vehicle. The application supports **multiple makes, focusing on recent
vehicles compatible with its standard OBD reads**.
Available data depends on what the vehicle actually advertises and returns;
its model year alone does not guarantee compatibility.

## Install and get started

1. Download [**OBD2-Dash-v0.17.apk**](https://github.com/nico579/obd2dash/releases/download/v0.17/OBD2-Dash-v0.17.apk)
   from the [0.17 release](https://github.com/nico579/obd2dash/releases/tag/v0.17)
   and open it on your phone. Allow the application opening the APK to install
   apps if Android asks. No GitHub account or token is required.
2. Open **… → Settings** and choose the adapter connection:

   | Connection | Preparation and choice in Settings |
   |---|---|
   | Wi-Fi | Connect the phone to the adapter's network, enter its IP/port, then leave Settings to apply changes. |
   | Classic Bluetooth | Pair the adapter in Android, refresh the device list in the application, then use **Connect** on the chosen device. |
   | BLE | Use **Find a BLE adapter**, grant the requested permissions, then select your adapter from the results. Its BLE profile must be supported. |

3. Once measurements have been discovered, choose up to six gauges in **Settings**,
   then reorder them with a long press and drag. **Graph** shows a measurement
   over time; **REC** starts or stops a CSV recording. Recordings are available
   from the **…** menu.

The application reconnects automatically to the saved target. If the adapter
responds but the vehicle provides no standard measurements, the application
shows that limitation. It does not invent values for missing data.

## Updates

### From the application — version 0.17 and later

1. Open **… → Updates**, then **Check now**. The adapter does not need to be
   connected; Internet access is required.
2. If a newer version is available, tap **Download update** and wait for the
   download and verification to finish. Progress is displayed; you can cancel
   or retry.
3. Finish any ongoing recording, scan or Smoke test, then tap **Install update**.
   Installation is blocked during these operations; checking and downloading
   remain available.
4. The first time you install from the application, allow **OBD2 Dash** to install
   its updates in Android's permission screen, return to the application and tap
   **Install update** again. Then confirm installation in Android.

If the installed version is already the latest, the application says it is up
to date and does not offer to reinstall it. Settings and recordings are retained
when updating the same package with the same signing certificate.

### Manual installation or versions without the Updates menu

Open the [latest release](https://github.com/nico579/obd2dash/releases/latest),
download **`OBD2-Dash-vX.Y.apk`** and open the file on your phone. Allow the
application opening the APK to install apps if Android asks, then confirm the
update. Keep the existing application installed to retain its settings and
recordings. Versions earlier than 0.17 need one manual update to gain the new menu.

The repository is **public**; no GitHub token is required.
**GitHub access** is optional in version 0.17, whose
help still describes the former private repository; remove an old token if it is
rejected. Entered tokens are encrypted with Android Keystore, excluded from Android
backups and device transfers, and are not forwarded on redirects to the CDN.

The 0.17 client looks specifically for **`app-debug.apk`**. This identical copy
is retained alongside the file named after the application. The asset name does
not change the Android package or signature. Before installation, the file must
match the published size and SHA-256, the installed package and its signers, and
a strictly newer version matching the release. The cache is checked again before
installation. Downloads use HTTPS with redirects restricted to GitHub hosts,
without changing the adapter's network. An interruption or invalid file allows
a retry.

Published versions and their changes are available in the
[releases](https://github.com/nico579/obd2dash/releases).

## Features

- **Live dashboard**: gauges for PIDs actually supported by the connected vehicle,
  discovered automatically at connection time. Select up to six in Settings.
  Large numeric values, scales and slightly transparent red needles extend from
  the centre to the scale, above the text, in both day and night themes.
  Numeric values use red **seven-segment** digits, like a vehicle display;
  labels, units and scale numbers retain their usual font.
  Values are rounded to whole numbers, enlarged and placed in the lower part of
  each gauge, with space between the unit and the rim. Thicker needles remain
  visible on the vehicle's dashboard. Scale numbers that could overlap the value
  area are omitted. Measurement, needle, graph and recording precision is retained.
  **Long press and drag** reorders gauges in reading order, left to right and then
  on the next row. The order is saved per VIN when available, with a shared
  configuration for vehicles with an unknown VIN. Existing choices and temporarily
  unavailable gauges are retained.
  The grid maximizes an equal diameter for every gauge in portrait and landscape;
  an incomplete final row is centred without enlarging its gauges.
  A single shared toolbar contains **Dashboard, Graph, recording, full screen
  and the … menu**. It is at the bottom in portrait or a short window, and on
  the side in landscape. Buttons are 64 dp high with 32 dp icons. Landscape
  defaults to full screen for gauges and graphs; the button is also available
  in portrait.
  The … menu contains **DTC, Scan, Smoke test, Settings and Updates**, plus
  reordering help, other measurements, recordings and disconnection.
  Choose gauges and Wi-Fi/Bluetooth mode in Settings. Recording controls remain
  accessible, including stopping during reconnection. Gauge scales are visual
  ranges, not warning thresholds.
  While waiting for a connection, the dashboard and graph show only **Waiting
  for connection** and a blinking Wi-Fi or Bluetooth symbol for the selected mode.
  The menu and stop-recording control remain accessible. The graph retains its
  points and selection if the same VIN is confirmed after reconnection. If the
  vehicle changes or its identity cannot be verified, history is cleared before
  new measurements are added.
- **Explicit discovery result**: reaching the adapter does not prove that the
  vehicle responded. An empty PID bitmap confirms a response without providing
  standard measurements. In that case, the application shows the limitation and
  does not start empty polling, unadvertised MIL monitoring or an automatic VIN
  read. A missing vehicle response keeps automatic connection attempts active.
- **DTC screen**: stored and pending codes, MIL status, readiness monitors,
  freeze-frame data and local history per vehicle, indexed by VIN. Each read is
  independent: a failed MIL read does not block stored/pending codes or the
  freeze-frame trigger code. Timestamps distinguish each result from the last
  complete scan. A rejected service retains its previous timestamped result or
  remains unknown. An ambiguous response never confirms the absence of faults.
  CSV files also retain MIL and code-read timestamps. These states are sampled
  from the cache without an extra read for each row. The freeze-frame trigger code
  and timestamp are exported even if no usable measurement accompanies it.
- **CSV recordings**: a connection loss pauses recording. Resuming the same file
  requires the same known VIN; a different or unverifiable VIN stops recording
  with an explicit reason and preserves the previous file. A new recording can
  still be started when the vehicle does not provide a VIN.
  A Smoke test can stop only the file it started; a manual REC started afterwards
  remains active.
  Recording sessions survive closing the interface while recording and the process
  remain active. The notification can **return to the application** or **stop
  recording**. Data freshness uses elapsed time, including sleep; CSV timestamps
  retain wall-clock time. A grouped response keeps its reception timestamp even
  if another measurement needs a slower separate read.
- **Automatic warning states in recordings**: MIL, glow-plug lamp (PID65), NOx
  warning activation (PID94), and vehicle/ECU WWH-OBD malfunction indication modes
  (PID90/91), depending on advertised parameters and actual responses.
  They are read from the first cycle, then every 30 seconds outside recording or
  at a target cadence of 5 seconds during REC, plus the time spent on serialized
  reads. Each state retains its read timestamp and the last attempt's timestamp
  and error. A missing read is never treated as a lamp being off.
  Additional columns are fixed when the CSV starts. Explicit non-support for
  the glow-plug lamp or NOx warning stops further reads for that connection.
  WWH modes and NOx warnings remain OBD states, without being converted into a
  specific instrument-cluster icon. DPF regeneration does not mean its warning
  lamp is on. ABS, airbag, brake, oil-pressure, battery-charge and other
  manufacturer-module lamps are not read. Events shorter than the polling cadence
  can be missed. No manually entered warning states are added.
- **Protocol detection**: CAN (ISO 15765-4) or non-CAN (SAE J1850, ISO 9141-2,
  ISO 14230 KWP2000), with DTC decoding for both cases.
- **Classic Bluetooth and BLE**: Auto/Classic/BLE selection and a ten-second,
  on-demand BLE search for unpaired adapters. The first supported GATT profile
  is FFF0/FFF1/FFF2, observed on the KONNWEI. Response notification subscription
  is confirmed before ELM commands; packets fit the minimum MTU and uncertain
  writes are not retransmitted. Auto keeps SPP for Classic/dual-mode devices
  and chooses BLE for BLE-only devices; devices selected from search results
  explicitly use BLE. BLE radio validation on Android is still pending.
- **Validated VIN**: length and alphabet checks, CAN counter verification and
  assembly of the five-part non-CAN format documented by ELM. Unexpected bytes
  are not removed to fabricate an identity; ambiguous responses remain unknown.
  The model year derived from a VIN is displayed as an **estimate**: its year
  code repeats in cycles and cannot establish the vehicle's year on its own.
- **Offline capture analysis** in Settings: extracts a Renault STD_A identity
  from a recorded KWP response to `2180`, checks its checksum and searches for
  an exact profile match. The protocol comes from the log, not from guessing
  based on the frame. The bundled manufacturer catalogue is empty, so no extra
  measurements are advertised.
- **Faults from a CAN capture** in Settings: locally reads a recorded response to
  `03` or `07`, attributes codes to ECUs and explicitly reports partial results.
  CAN11/CAN29 and stored/pending choices come from the log. This analysis does not
  replace a current diagnostic read or modify fault history.

## Known limitations and design choices

- **No fault-code clearing.** This feature has deliberately not been implemented;
  adding it requires explicit authorization.
- **Responses without ECU identity.** Headers are disabled (`ATH0`). Discovery
  combines valid capability bitmaps but cannot assign them to an ECU.
  Contradictory measurements are rejected, including grouped requests; identical
  duplicates remain readable. MIL is combined and codes from valid responses are
  merged. A malformed response or adjacent rejection prevents the diagnostic
  result from being declared complete; observed codes remain in the error.
  ECU attribution for connected diagnostics is still pending.
  The preparatory `ATH1` reassembler checks bytes, lengths and sequences per ECU
  with explicit CAN11/CAN29 selection. It has real CAN11 replays from the SEAT
  adapter, but is inactive and lacks CAN29 and multi-ECU hardware validation.
  Its format is limited to Classical CAN with normal ISO-TP addressing and
  no displayed DLC.
- **Adapter IP/port entered manually**: no automatic network discovery.
- **Exploratory manufacturer scanning is disabled**, including in the Smoke test.
  Existing captures remain viewable; an arbitrary identifier range does not
  replace a documented read profile.
- **Automatic failures and discovery responses are logged**, without showing
  technical details on the dashboard.
- **Process termination**: closing the interface keeps an active recording alive,
  but process termination or force-stop does not allow automatic reconstruction
  of its session. Offline test doubles do not validate real phone, notification
  or physical-adapter behaviour.

## Build and install

Building requires **JDK 17**, **Android SDK 34** and the repository's Gradle wrapper.
Examples follow the project's RTK command convention. On Windows / PowerShell:

```powershell
rtk proxy ./gradlew.bat assembleDebug
rtk proxy ./gradlew.bat installDebug
```

`installDebug` requires an Android 8.0 or later device connected with USB debugging
enabled. The local APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
On Linux/macOS, use `./gradlew` instead of `./gradlew.bat`.

GitHub APKs use the tracked **debug** key to retain the same signature between
versions. That key is public with the repository; it is not a protected signing
identity for a production distribution.

The bundled [DSEG7 Modern Bold](https://github.com/keshikan/DSEG/releases/tag/v0.46)
font (DSEG 0.46), created by keshikan, is distributed under the SIL Open Font
License 1.1. Its licence is included in the APK and in
[`app/src/main/assets/licenses/DSEG-LICENSE.txt`](app/src/main/assets/licenses/DSEG-LICENSE.txt).

## Tests

```powershell
rtk proxy ./gradlew.bat testDebugUnitTest
rtk proxy ./gradlew.bat lintDebug
```

Validation of **0.17** includes **433 unit tests**, **47 renders**, a successful
build and lint with no errors or warnings. Actual downloading and cancellation
were checked against the public repository using the production updater code.
Tests cover measurements, diagnostics, reconnections, recordings and updates.
They use simulated adapters on localhost, without connecting to a vehicle.
They do not replace hardware validation on a phone and a physical adapter.
