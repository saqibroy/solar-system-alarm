# WAPDA Alarm

Independent alarm path for a Knoxhybrid/ShineMonitor inverter installation. The server polls ShineMonitor for active warnings and sends Firebase Cloud Messaging data pushes. The Android app receives those pushes and rings a loud foreground alarm until stopped locally or cleared by the server.

## Layout

```text
/server   Python ShineMonitor poller, FCM sender, token registration endpoint, health endpoint
/android  Kotlin Android app for the phone that must ring
```

## Setup Order

1. Create a Firebase project, add an Android app with package `com.saqib.wapdaalarm`, and create a Firebase Admin service-account JSON.
2. Download `google-services.json` and put it at `android/app/google-services.json`.
3. Build and sideload the Android app, enter the Oracle server URL and registration secret, then register the phone.
4. Configure `/server/.env` on the Oracle VM with ShineMonitor credentials, Knoxhybrid `companykey`, Firebase service-account path, and alarm definitions.
5. Install the server as a `systemd` service and check `/health`.
6. Simulate or trigger `LINE_FAIL`, confirm FCM arrives, and confirm the phone rings over lock screen/silent mode.

## Reliability Notes

This intentionally does not rely on Knoxhybrid push notifications. The server polls Eybond/ShineMonitor directly using the reverse-engineered `shinemonitor-api` package for auth/signing and calls `queryDeviceWarning` for alarm status.

The exact warning descriptions/codes for `LINE_FAIL` and `PV_LOSS` must be verified against your own account before relying on it. The parser treats a warning as active when it matches a configured keyword and has no `cts` clear timestamp.

## Docs

See [server/README.md](server/README.md) and [android/README.md](android/README.md).

## Deployment

Production runs on the Oracle VM with Docker Compose from `docker-compose.yml`.
The VM has a `systemd` timer that checks this public GitHub repo's `main` branch
about once per minute and rebuilds/restarts the Compose service when `main`
changes. Secrets stay on the VM in `/etc/wapda-alarm/` and are not committed.
