from __future__ import annotations

import logging
from datetime import timedelta
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, messaging

from .config import AlarmDefinition
from .logging_config import extra
from .state import Transition

LOGGER = logging.getLogger(__name__)


class FcmAlarmSender:
    def __init__(self, credentials_path: Path) -> None:
        if not firebase_admin._apps:
            cred = credentials.Certificate(str(credentials_path))
            firebase_admin.initialize_app(cred)

    def send_transition(
        self,
        transition: Transition,
        alarm: AlarmDefinition,
        tokens: list[str],
    ) -> None:
        if not tokens:
            LOGGER.warning("fcm_no_tokens_configured", extra=extra(alarm=transition.alarm))
            return

        data = {
            "type": transition.event_type,
            "alarm": transition.alarm,
            "severity": alarm.severity,
            "timestamp": transition.timestamp,
            "active_ids": ",".join(transition.active_ids),
        }

        for token in tokens:
            message = messaging.Message(
                token=token,
                data=data,
                android=messaging.AndroidConfig(
                    priority="high",
                    ttl=timedelta(minutes=30),
                ),
            )
            try:
                result = messaging.send(message)
                LOGGER.info(
                    "fcm_send_ok",
                    extra=extra(alarm=transition.alarm, event_type=transition.event_type, result=result),
                )
            except Exception:
                LOGGER.exception(
                    "fcm_send_failed",
                    extra=extra(alarm=transition.alarm, event_type=transition.event_type),
                )
