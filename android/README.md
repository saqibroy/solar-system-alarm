# WAPDA Alarm Android App

Single-purpose Android app for the phone in Pakistan. It receives Firebase Cloud Messaging data messages from the server and starts a loud foreground alarm with a full-screen lock-screen UI.

## Firebase Config

In Firebase, add an Android app with package:

```text
com.saqib.wapdaalarm
```

Download the generated `google-services.json` and place it here:

```text
android/app/google-services.json
```

The file is intentionally ignored by git because it is project-specific. The app uses the Google services Gradle plugin, so you do not need to copy Firebase values into XML manually.

In the Firebase setup wizard, you only need Cloud Messaging for this app. Do not add Firebase Analytics unless you personally want it; this app does not use analytics.

## Build

Open `/android` in Android Studio and build the `app` module, or run:

```bash
cd android
./gradlew assembleDebug
```

The build requires `app/google-services.json`.

Sideload:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Setup On The Phone

1. Open WAPDA Alarm.
2. Grant notification permission, DND access, and battery optimization exemption.
3. Enter the server URL, for example `http://158.180.30.164:8088`.
4. Enter the same `REGISTRATION_SECRET` used on the server.
5. Tap `Connect`.
6. Tap `Test Alarm for 10 seconds`.
7. On Xiaomi, Oppo, Vivo, Realme, Poco, Redmi and similar devices, manually enable Autostart, unrestricted battery/background use, and lock the app in Recents.

## Alert Modes

The app can handle each incoming alert as:

- `Alarm`: start the loud full-screen alarm.
- `Notify`: show a normal notification only.
- `Off`: ignore that alert on this phone.

`LINE_FAIL` defaults to `Alarm`. Restored/clear messages default to a normal notification.

## FCM Payloads

Start:

```json
{"type":"alarm_start","alarm":"LINE_FAIL","severity":"critical","timestamp":"2026-07-11T12:00:00Z"}
```

Stop:

```json
{"type":"alarm_stop","alarm":"LINE_FAIL","timestamp":"2026-07-11T12:10:00Z"}
```

`PV_LOSS` uses the same start/stop pattern.

## End-To-End Test Plan

1. Confirm `/health` shows `registered_tokens: 1`.
2. Press local `Test Alarm for 10 seconds`; confirm max volume, vibration, lock-screen UI, and STOP.
3. Send a real or simulated `alarm_start` from the server and confirm the phone rings while unlocked.
4. Lock the phone, send another `alarm_start`, and confirm full-screen alarm appears.
5. Send `alarm_stop`; confirm sound stops and volume restores.
6. Force-kill the app, wait a few minutes, send `alarm_start`, and confirm FCM still wakes it.
7. Reboot the phone, wait for network connectivity, send `alarm_start`, and confirm it still wakes.
8. Trigger real grid loss briefly, verify `LINE_FAIL` appears in server logs, then restore grid and verify the clear message stops the alarm.
