# Peek

Peek is a standalone Android app that connects to a **Keep CC_23 (C1 Mini)** spin bike over BLE, records ride data, and exports Garmin FIT files — ready for Strava upload.

It also acts as an **FTMS bridge**: re-broadcasts the bike as a standard Bluetooth FTMS Indoor Bike so Zwift, Mywhoosh, and TrainerRoad can connect through your phone.

## Features

- **BLE connection** to Keep C1 Mini bikes — scan, connect, handshake
- **Real-time metrics** — RPM, power (watts), resistance, speed, distance, calories
- **Heart rate** — connect a standard BLE HR strap (0x180D)
- **Ride recording** — per-second data accumulation with live aggregates
- **FIT export** — Garmin FIT files with cycling activity records, gear-change events, and HR data
- **Strava upload** — OAuth refresh-token flow, upload directly from the app
- **FTMS bridge** — phone acts as a BLE peripheral, exposing the bike as a standard FTMS Indoor Bike
- **ERG + SIM modes** — target power (ERG) and outdoor simulation (grade, wind, rolling resistance) for Zwift
- **Power model calibration** — learn your bike's resistance→watt curve for accurate ERG/SIM
- **Ride history** — local Room database with per-session stats and Strava upload status

## Requirements

- Android 8.0+ (API 26)
- A Keep C1 Mini spin bike (CC_23 model)
- BLE HR strap (optional)
- Strava API credentials (optional, for upload)

## Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (needs signing config)
./gradlew :app:testDebugUnitTest      # run unit tests
```

## Usage

1. **Scan** — open the app, grant BLE permissions. It scans for nearby Keep bikes.
2. **Connect** — tap a bike. The app runs the auth handshake and shows live metrics.
3. **Start** — press Start. The bike screen shows "press knob" — push the physical knob on the bike to begin.
4. **Ride** — metrics update in real time. HR chart builds if a strap is connected.
5. **Stop** — press Stop. A FIT file is written automatically with a session summary.
6. **Upload** — tap "Upload to Strava" on the session screen (requires client ID, secret, and refresh token in Settings).
7. **FTMS** — enable the FTMS bridge in Settings. Zwift will see "Peek Bike" as an available trainer.

## Settings

| Setting | Description |
|---|---|
| User ID / Device ID | Identifiers for the bike handshake (auto-generated on first launch) |
| Weight (kg) | Rider weight — used for power calibration and SIM physics |
| Strava credentials | Client ID, secret, refresh token for upload |
| FTMS Bridge | Toggle the BLE peripheral that re-broadcasts to Zwift |
| Debug log | Write per-second CSV debug logs for troubleshooting |

## Tech stack

- **Kotlin / Jetpack Compose** — single-module Android app
- **Nordic BLE 2.7.x** — BLE transport layer
- **Garmin FIT SDK 21.205.0** — FIT file generation
- **Room** — local ride history database
- **DataStore** — preferences persistence
- **OkHttp** — Strava API client
- **JUnit 4 + MockK + Turbine** — test stack

## License

MIT