# Product Ideas

Current production behavior is intentionally narrow: `LINE_FAIL` starts a loud alarm, and clear/restored sends a normal notification.

Useful next features:

- Alert mode per event: loud alarm, normal notification, or off. The Android app already supports this locally.
- Battery low while grid is out: alarm if battery percent drops below a threshold.
- High load while on battery: alarm when AC output load is high after `LINE_FAIL`.
- PV loss during daytime: notify or alarm when solar input is lost and battery is discharging.
- Inverter/cloud stale data: notify if ShineMonitor data has not updated for a configurable time.
- Daily solar summary: normal notification with PV energy, grid outage time, and minimum battery level.
- Escalation: repeat the alarm every few minutes if the user does not press STOP.
- Multi-phone roles: father's phone gets alarms, other phones get normal notifications.
- In-app server status: last poll time, last `LINE_FAIL` state, and last cloud push result.

Implementation notes:

- Data alerts require polling `querySPDeviceLastData` in addition to `queryDeviceWarning`.
- The server should own thresholds and real alert decisions; the phone should only decide how disruptive each alert is.
- Keep `LINE_FAIL` as the only default loud alarm until the extra data rules are tested against the real inverter.
