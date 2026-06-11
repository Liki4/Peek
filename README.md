# Peek

Peek is a standalone Android app that connects to a **Keep CC_23 (C1 Mini)** spin bike over BLE, records ride data, and exports Garmin FIT files — ready for Strava upload.

It also acts as an **FTMS bridge** (⚠️ 待测试): re-broadcasts the bike as a standard Bluetooth FTMS Indoor Bike so Zwift, Mywhoosh, and TrainerRoad can connect through your phone. **此功能已实现但未经实际测试验证，上次测试未成功连上，暂无新的测试机会。**

## Features

- **BLE connection** to Keep C1 Mini bikes — scan, connect, handshake
- **Real-time metrics** — RPM, power (watts), resistance, speed, distance, calories
- **Heart rate** — connect a standard BLE HR strap (0x180D)
- **Ride recording** — per-second data accumulation with live aggregates
- **FIT export** — Garmin FIT files with cycling activity records, gear-change events, and HR data
- **Strava upload** — OAuth refresh-token flow, upload directly from the app
- **FTMS bridge** (⚠️ 待测试) — phone acts as a BLE peripheral, exposing the bike as a standard FTMS Indoor Bike
- **ERG + SIM modes** (⚠️ 待测试) — target power (ERG) and outdoor simulation (grade, wind, rolling resistance) for Zwift
- **Power model calibration** (⚠️ 待测试) — learn your bike's resistance→watt curve for accurate ERG/SIM
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

## Strava 凭据获取教程

Peek 使用 Strava OAuth refresh-token 流程上传 FIT 文件。需要三个值：Client ID、Client Secret、Refresh Token。

### 1. 创建 Strava API 应用

1. 登录 [strava.com/settings/api](https://www.strava.com/settings/api)
2. 填写表单：
   - **Application Name**: `Peek`（或任意名称）
   - **Website**: 可以填 `http://localhost`
   - **Authorization Callback Domain**: `localhost`
   - 上传一张应用图标（可选）
3. 点击 **Create**，记录下 **Client ID** 和 **Client Secret**

### 2. 获取 Refresh Token

Refresh Token 需要通过 OAuth 授权流程获取。最简单的方式是用浏览器完成一次授权：

在浏览器中访问以下 URL（替换 `YOUR_CLIENT_ID`）：

```
https://www.strava.com/oauth/authorize?client_id=YOUR_CLIENT_ID&response_type=code&redirect_uri=http://localhost&approval_prompt=force&scope=activity:write
```

点击 **Authorize** 后，浏览器会重定向到 `localhost`，URL 里会有一个 `code` 参数：

```
http://localhost/?state=&code=abc123...&scope=read,activity:write
```

复制 `code` 的值，然后在终端用 curl 换取 token：

```bash
curl -X POST https://www.strava.com/oauth/token \
  -d client_id=YOUR_CLIENT_ID \
  -d client_secret=YOUR_CLIENT_SECRET \
  -d code=PASTED_CODE \
  -d grant_type=authorization_code
```

返回的 JSON 中包含 `refresh_token`。把这个值和 Client ID、Client Secret 一起填入 Peek 的 Settings 页面即可。

**注意**：Refresh Token 长期有效，只需配置一次。Peek 会在每次上传前自动用它换新的 Access Token。

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