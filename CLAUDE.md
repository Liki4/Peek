# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Context

Peek Android app — standalone Kotlin/Compose app that connects to a Keep CC_23 (C1 Mini) spin bike over BLE, records ride data, produces Garmin FIT files for Strava upload, and re-broadcasts as a standard FTMS Indoor Bike for Zwift.

The BLE protocol was reverse-engineered from the Keep APK and validated against a real bike (SN 10200901). The protocol spec and reference Python implementation live in the parent project (`Keep/`) alongside captured test fixtures. CC_23 invariants to watch for: OBSERVE uses method `0x01` not `0x04`, speed is derived from distance deltas, `TrainAttribute` is event-driven (resistance only), and CRC is CRC-16/XMODEM LE.

## Build & test

```bash
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:testDebugUnitTest      # run all unit tests (JVM, no device needed)
./gradlew :app:testDebugUnitTest --tests "io.github.liki4.peek.protocol.KeepPacketTest"  # single test class
```

Tests are plain JUnit 4 + MockK + Turbine. No Android device or emulator needed — protocol, FIT, FTMS, and HR parsing tests all run on the JVM against captured wire fixtures from `test_logs/`.

System Gradle 8.13 works; the wrapper bootstraps on first network access. minSdk 26, targetSdk 35, Kotlin 2.1.0.

## Package architecture

| Package | Role |
|---|---|
| `protocol/` | Keep BLE packet layer: CRC-16/XMODEM, framing, protobuf-lite message codec, field parsers for 106/* resources. Pure Kotlin, zero Android deps. |
| `ble/` | Nordic BLE 2.7.x transport: `KeepBleManager` (raw GATT), `KeepBikeClient` (coroutine API with sub_seq-correlated request/response), `BikeScanner`, `Handshake`. |
| `hr/` | Standard BLE HRP client (0x180D / 0x2A37) for external HR strap. Independent of bike GATT. |
| `fit/` | `RideDataBuilder` (per-second accumulator), `RideData` (snapshot struct), `FitWriter` (Garmin FIT SDK output). |
| `ftms/` | FTMS server-side bridge: re-broadcasts the Keep bike as a standard FTMS Indoor Bike so Zwift/Mywhoosh/TrainerRoad can connect. Includes `ErgController` (ERG + SIM physics), `PowerModel` (resistance→watt calibration), `FtmsBridge` (GATT server + BLE advertiser), `CalibrationRunner`. |
| `ride/` | `RideRepository` (process-wide singleton — owns bike client, HR client, FTMS bridge, poll loop, state machine), `RideState` / `RideUiState` (state machine + UI model), `RideForegroundService`, `Settings` (DataStore), `RideLogger` (debug CSV logger). |
| `strava/` | Strava OAuth refresh + FIT upload via OkHttp. |
| `history/` | Room database for per-ride session records (duration, distance, avg/max HR, FIT path, Strava upload status). |
| `ui/` | Compose UI: `PeekRoot` (state-driven screen routing), screens (Scan, Connect, Ride, Session, Settings, History, Calibration), components (HrChart, MetricTile), theme, permissions gate. |

## Architecture: the data-flow spine

1. **`RideRepository`** is a process-wide singleton (`RideRepository.get(context)`). It owns all BLE clients, the poll loop, FTMS bridge, and `RideDataBuilder`. Reconstructing BLE clients across config changes would drop the GATT session.
2. **Single `StateFlow<RideUiState>`** drives the UI. `PeekRoot` observes `repo.state` and picks the active screen via a `when` on `ui.state` (`RideState` enum: IDLE → SCANNING → CONNECTING → CONNECTED → WAITING_KNOB → TRAINING → PAUSED → STOPPED → EXPORTING`).
3. **All trigger methods on `RideRepository` are non-suspend** — they `scope.launch` on a long-lived `SupervisorJob` scope, not the caller's composition scope. Compose recompositions during state transitions would otherwise cancel UI-launched coroutines mid-handshake.
4. **1 Hz poll loop** runs whenever the bike is connected (not just while recording). `TrainData` (106/7) is fetched each tick. Recording (`builder.tick()`) is gated on `RideState.TRAINING`. Timing is wall-clock-anchored to avoid drift.
5. **`RideDataBuilder`** accumulates per-second arrays (resistance, rpm, watt, derived-speed, hr) + aggregates. On stop, `build()` produces a `RideData` snapshot that `FitWriter` serializes.

## Testing: captured wire fixtures

Tests in `protocol/`, `fit/`, `ftms/`, and `hr/` use `Fixtures.kt` — exact byte sequences captured from a live CC_23 (SN 10200901) session. Any change that breaks these tests breaks real-firmware compatibility. The fixture source is `test_logs/keep_test_20260530_152901.jsonl` (in the parent Keep/ project).

## CI/CD

GitHub Actions workflow at `.github/workflows/release.yml` — triggers on `v*` tag pushes, runs unit tests, builds release APK, signs it (requires `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` secrets), and attaches to a GitHub Release.