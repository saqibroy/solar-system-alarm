from __future__ import annotations

import logging
import time
from datetime import datetime, timezone

from shinemonitor_api import _protocol as shine_protocol

from .config import WatcherConfig
from .data_alerts import (
    evaluate_data_alerts,
    local_summary_date,
    should_send_daily_summary,
    should_send_status_push,
)
from .fcm import FcmAlarmSender
from .health import RuntimeStatus
from .logging_config import extra
from .registry import TokenRegistry
from .shinemonitor_client import ShineMonitorWarningsClient, active_warning_ids
from .state import AlarmStateStore

LOGGER = logging.getLogger(__name__)


class AlarmPoller:
    def __init__(
        self,
        config: WatcherConfig,
        state_store: AlarmStateStore,
        token_registry: TokenRegistry,
        fcm_sender: FcmAlarmSender,
        status: RuntimeStatus,
    ) -> None:
        self.config = config
        self.state_store = state_store
        self.token_registry = token_registry
        self.fcm_sender = fcm_sender
        self.status = status
        self.client = ShineMonitorWarningsClient(config)
        self.alarm_by_name = {alarm.name: alarm for alarm in config.alarms}

    def run_forever(self) -> None:
        self.client.login()
        self.client.ensure_target()
        while True:
            self.poll_once()
            time.sleep(self.config.poll_interval_seconds)

    def poll_once(self) -> None:
        now_dt = datetime.now(timezone.utc)
        now = now_dt.isoformat()
        try:
            warnings = self.client.fetch_warnings()
            active = active_warning_ids(warnings, self.config.alarms)
            summary = "No inverter snapshot fetched"
            snapshot = None
            try:
                snapshot = self.client.fetch_last_data()
                evaluated = evaluate_data_alerts(snapshot, active, self.config.data_alerts, now_dt)
                for alarm_name, active_ids in evaluated.active.items():
                    active.setdefault(alarm_name, set()).update(active_ids)
                summary = evaluated.summary
                self.status.last_snapshot_at = snapshot.timestamp.isoformat()
            except Exception:
                LOGGER.exception("snapshot_fetch_failed")

            transitions = self.state_store.apply(active)
            self.status.last_poll_at = now
            self.status.last_poll_ok = True
            self.status.last_error = None
            self.status.poll_count += 1
            self.status.last_line_fail_active = bool(active.get("LINE_FAIL"))
            LOGGER.info(
                "poll_ok",
                extra=extra(
                    warning_count=len(warnings),
                    active={key: sorted(value) for key, value in active.items()},
                    transitions=[t.__dict__ for t in transitions],
                    snapshot_at=snapshot.timestamp.isoformat() if snapshot else None,
                ),
            )
            for transition in transitions:
                alarm = self.alarm_by_name[transition.alarm]
                results = self.fcm_sender.send_transition(
                    transition,
                    alarm,
                    self.token_registry.tokens(),
                    message=self._message_for_transition(transition.alarm, transition.event_type, summary),
                )
                if results:
                    self.status.last_push_result = results[-1]
            self._send_periodic_status(now_dt, active, summary)
            self._send_daily_summary_if_due(now_dt, summary)
        except shine_protocol.ShineMonitorAuthError:
            LOGGER.warning("shinemonitor_auth_expired_relogin")
            self.client.login()
            self.status.last_error = "auth expired; relogin attempted"
            self.status.last_poll_ok = False
        except Exception as exc:
            self.status.last_error = str(exc)
            self.status.last_poll_ok = False
            LOGGER.exception("poll_failed")

    def _send_periodic_status(
        self,
        now: datetime,
        active: dict[str, set[str]],
        summary: str,
    ) -> None:
        last_sent_at = self.state_store.get_meta("last_status_push_at")
        if not should_send_status_push(
            now,
            last_sent_at,
            self.config.data_alerts.status_push_interval_minutes,
        ):
            return
        results = self.fcm_sender.send_custom(
            event_type="server_status",
            alarm="SERVER_STATUS",
            severity="info",
            timestamp=now.isoformat(),
            tokens=self.token_registry.tokens(),
            message=summary,
            extra_data={
                "last_poll_at": self.status.last_poll_at or "",
                "line_fail_active": str(bool(active.get("LINE_FAIL"))).lower(),
                "last_push_result": self.status.last_push_result or "",
            },
        )
        self.state_store.set_meta("last_status_push_at", now.isoformat())
        if results:
            self.status.last_push_result = results[-1]

    def _send_daily_summary_if_due(self, now: datetime, summary: str) -> None:
        last_date = self.state_store.get_meta("last_daily_summary_date")
        if not should_send_daily_summary(now, self.config.data_alerts, last_date):
            return
        results = self.fcm_sender.send_custom(
            event_type="daily_summary",
            alarm="DAILY_SUMMARY",
            severity="info",
            timestamp=now.isoformat(),
            tokens=self.token_registry.tokens(),
            message=summary,
            extra_data={"last_poll_at": self.status.last_poll_at or ""},
        )
        self.state_store.set_meta("last_daily_summary_date", local_summary_date(now, self.config.data_alerts))
        if results:
            self.status.last_push_result = results[-1]

    def _message_for_transition(self, alarm: str, event_type: str, summary: str) -> str:
        if event_type == "alarm_stop":
            return {
                "LINE_FAIL": "LINE_FAIL cleared. Electricity is back.",
                "BATTERY_LOW": "Battery-low condition cleared.",
                "HIGH_LOAD": "High-load condition cleared.",
                "PV_LOSS": "PV loss cleared.",
                "STALE_DATA": "Inverter cloud data is fresh again.",
            }.get(alarm, f"{alarm} cleared.")
        return {
            "LINE_FAIL": "Grid power is out. Turn off the air conditioner before the battery drains.",
            "BATTERY_LOW": "Battery is low while grid power is out. Reduce load now.",
            "HIGH_LOAD": "Load is high while running on battery. Turn off heavy appliances.",
            "PV_LOSS": "Solar input is low during daytime while the battery is draining.",
            "STALE_DATA": "ShineMonitor data is stale. Check internet/cloud connectivity.",
        }.get(alarm, summary)
