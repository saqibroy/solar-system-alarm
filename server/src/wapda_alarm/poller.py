from __future__ import annotations

import logging
import time
from datetime import datetime, timezone

from shinemonitor_api import _protocol as shine_protocol

from .config import WatcherConfig
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
        now = datetime.now(timezone.utc).isoformat()
        try:
            warnings = self.client.fetch_warnings()
            active = active_warning_ids(warnings, self.config.alarms)
            transitions = self.state_store.apply(active)
            self.status.last_poll_at = now
            self.status.last_poll_ok = True
            self.status.last_error = None
            self.status.poll_count += 1
            LOGGER.info(
                "poll_ok",
                extra=extra(
                    warning_count=len(warnings),
                    active={key: sorted(value) for key, value in active.items()},
                    transitions=[t.__dict__ for t in transitions],
                ),
            )
            for transition in transitions:
                alarm = self.alarm_by_name[transition.alarm]
                self.fcm_sender.send_transition(transition, alarm, self.token_registry.tokens())
        except shine_protocol.ShineMonitorAuthError:
            LOGGER.warning("shinemonitor_auth_expired_relogin")
            self.client.login()
            self.status.last_error = "auth expired; relogin attempted"
            self.status.last_poll_ok = False
        except Exception as exc:
            self.status.last_error = str(exc)
            self.status.last_poll_ok = False
            LOGGER.exception("poll_failed")
