# WAPDA Alarm Server

Small Python service for the Oracle VM. It polls ShineMonitor every 60 to 120 seconds, detects alarm state transitions, sends high-priority FCM data pushes, and exposes:

- `GET /health`
- `POST /register` with `Authorization: Bearer <REGISTRATION_SECRET>`

## ShineMonitor API

This uses `shinemonitor-api` for login/signing and its generated action support. Alarm polling is based on Eybond's documented `queryDeviceWarning` action, whose response includes `warning[x].desc`, `warning[x].code`, `warning[x].gts`, and optional `warning[x].cts` clear time.

Reference:

- https://github.com/davidsmfreire/shinemonitor-api
- https://api.shinemonitor.com/chapter5/queryDeviceWarning.html

## Obtain the Knoxhybrid `companykey`

The `companykey` is app-specific. Do not guess it.

1. Use your own Knoxhybrid account only.
2. Install a trusted proxy such as mitmproxy or HTTP Toolkit on a test phone/emulator.
3. Configure Android Wi-Fi proxy to the proxy host and install the proxy CA certificate if the app accepts user CAs.
4. Log in to Knoxhybrid and inspect the login request to `shinemonitor.com`/`api.shinemonitor.com`.
5. Copy the `company-key` or `companykey` query parameter into `SHINEMONITOR_COMPANY_KEY`.
6. Also record the app context query parameters if they differ from the default, especially `_app_id_`, `_app_version_`, `source`, `lang`, and `i18n`; put the full suffix into `SHINEMONITOR_SUFFIX_CONTEXT`.

If TLS interception fails, inspect the Knoxhybrid APK with JADX and search for `company-key`, `companykey`, `_app_id_`, or `authSource`. The key is a vendor app identifier, not your password.

## Firebase Setup

1. Create a Firebase project on the free tier.
2. Add Android app package `com.saqib.wapdaalarm`.
3. In Project settings, create a service-account private key JSON.
4. Copy that JSON to the VM, for example:

```bash
sudo mkdir -p /etc/wapda-alarm
sudo cp firebase-service-account.json /etc/wapda-alarm/firebase-service-account.json
sudo chmod 600 /etc/wapda-alarm/firebase-service-account.json
```

## Configuration

Copy `.env.example` to `/etc/wapda-alarm/wapda-alarm.env` and edit it:

```bash
sudo mkdir -p /etc/wapda-alarm /var/lib/wapda-alarm
sudo cp .env.example /etc/wapda-alarm/wapda-alarm.env
sudo nano /etc/wapda-alarm/wapda-alarm.env
sudo chmod 600 /etc/wapda-alarm/wapda-alarm.env
```

Important values:

- `SHINEMONITOR_USERNAME`, `SHINEMONITOR_PASSWORD`
- `SHINEMONITOR_COMPANY_KEY`
- `SHINEMONITOR_DATALOGGER_PN`, `SHINEMONITOR_DEVICE_SN`, `SHINEMONITOR_DEVICE_CODE`, `SHINEMONITOR_DEVICE_ADDR`
- `REGISTRATION_SECRET`
- `FIREBASE_CREDENTIALS=/etc/wapda-alarm/firebase-service-account.json`

Alarm keywords are configurable:

```bash
ALARM_DEFINITIONS_JSON=[{"name":"LINE_FAIL","keywords":["LINE_FAIL","Line Fail"],"severity":"critical"},{"name":"PV_LOSS","keywords":["PV Loss","PV_LOSS","PV input lost"],"severity":"warning"}]
```

## Local Run

```bash
cd server
python3 -m venv .venv
. .venv/bin/activate
pip install -e .
python -m wapda_alarm --env-file /etc/wapda-alarm/wapda-alarm.env --once
python -m wapda_alarm --env-file /etc/wapda-alarm/wapda-alarm.env
```

Health:

```bash
curl http://127.0.0.1:8088/health
```

Register a phone token manually if needed:

```bash
curl -X POST http://127.0.0.1:8088/register \
  -H "Authorization: Bearer $REGISTRATION_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"token":"FCM_TOKEN","device_name":"Dad phone"}'
```

## Oracle VM Install With Docker Compose

```bash
sudo mkdir -p /etc/wapda-alarm /var/lib/wapda-alarm
sudo cp wapda-alarm.env /etc/wapda-alarm/wapda-alarm.env
sudo cp firebase-service-account.json /etc/wapda-alarm/firebase-service-account.json
sudo chown root:opc /etc/wapda-alarm/*
sudo chmod 640 /etc/wapda-alarm/*

git clone https://github.com/saqibroy/solar-system-alarm.git /opt/solar-system-alarm
cd /opt/solar-system-alarm
docker compose -p solar-system-alarm up -d --build
docker compose -p solar-system-alarm ps
```

The Compose service binds `/health` to `127.0.0.1:8088` on the VM. Firebase
delivery is outbound, so public inbound access to port `8088` is not required.

## Verify Before Relying On This

- Confirm Knoxhybrid `companykey` and suffix context by a real login capture.
- Confirm the selected device `pn`, `sn`, `devcode`, and `devaddr`.
- Capture a real `LINE_FAIL` warning and confirm the keyword appears in `desc` or `code`.
- Capture a real clear event and confirm active warnings disappear or include `cts`.
- Confirm `PV_LOSS` wording/code from your inverter model.
- Confirm FCM data pushes wake the exact phone model after force-kill and reboot.
