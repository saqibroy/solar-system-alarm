from __future__ import annotations

import logging
from datetime import timedelta
from pathlib import Path
from typing import Optional

import firebase_admin
from firebase_admin import credentials, messaging

from .config import AlarmDefinition
from .logging_config import extra
from .state import Transition

LOGGER = logging.getLogger(__name__)


class FcmAlarmSender:
    def __init__(self, credentials_path: Path, topic: Optional[str] = None) -> None:
        if not firebase_admin._apps:
            cred = credentials.Certificate(str(credentials_path))
            firebase_admin.initialize_app(cred)
        self.topic = topic

    def send_transition(
        self,
        transition: Transition,
        alarm: AlarmDefinition,
        tokens: list[str],
    ) -> None:
        if not tokens and not self.topic:
            LOGGER.warning("fcm_no_targets_configured", extra=extra(alarm=transition.alarm))
            return

        data = {
            "type": transition.event_type,
            "alarm": transition.alarm,
            "severity": alarm.severity,
            "timestamp": transition.timestamp,
            "active_ids": ",".join(transition.active_ids),
        }

        if tokens:
            for token in tokens:
                self._send_message(
                    messaging.Message(
                        token=token,
                        data=data,
                        android=messaging.AndroidConfig(
                            priority="high",
                            ttl=timedelta(minutes=30),
                        ),
                    ),
                    transition,
                    target="token",
                )
            return

        topic_message = (
            messaging.Message(
                topic=self.topic,
                data=data,
                android=messaging.AndroidConfig(
                    priority="high",
                    ttl=timedelta(minutes=30),
                ),
            )
            if self.topic
            else None,
        )
        self._send_message(
            topic_message,
            transition,
            target=f"topic:{self.topic}",
        )

    def _send_message(
        self,
        message: Optional[messaging.Message],
        transition: Transition,
        target: str,
    ) -> None:
        if message is None:
            return
        try:
            result = messaging.send(message)
            LOGGER.info(
                "fcm_send_ok",
                extra=extra(
                    alarm=transition.alarm,
                    event_type=transition.event_type,
                    target=target,
                    result=result,
                ),
            )
        except Exception:
            LOGGER.exception(
                "fcm_send_failed",
                extra=extra(alarm=transition.alarm, event_type=transition.event_type, target=target),
            )
