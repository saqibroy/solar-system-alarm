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
        message: Optional[str] = None,
    ) -> list[str]:
        if not tokens and not self.topic:
            LOGGER.warning("fcm_no_targets_configured", extra=extra(alarm=transition.alarm))
            return []

        data = {
            "type": transition.event_type,
            "alarm": transition.alarm,
            "severity": alarm.severity,
            "timestamp": transition.timestamp,
            "active_ids": ",".join(transition.active_ids),
        }
        if message:
            data["message"] = message

        results: list[str] = []
        if tokens:
            for token in tokens:
                result = self._send_message(
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
                if result:
                    results.append(result)
            return results

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
            else None
        )
        result = self._send_message(
            topic_message,
            transition,
            target=f"topic:{self.topic}",
        )
        return [result] if result else []

    def send_custom(
        self,
        *,
        event_type: str,
        alarm: str,
        severity: str,
        timestamp: str,
        tokens: list[str],
        message: str,
        extra_data: Optional[dict[str, str]] = None,
    ) -> list[str]:
        if not tokens and not self.topic:
            LOGGER.warning("fcm_no_targets_configured", extra=extra(alarm=alarm))
            return []

        data = {
            "type": event_type,
            "alarm": alarm,
            "severity": severity,
            "timestamp": timestamp,
            "message": message,
        }
        if extra_data:
            data.update(extra_data)

        transition = Transition(alarm=alarm, event_type=event_type, active_ids=(), timestamp=timestamp)
        results: list[str] = []
        if tokens:
            for token in tokens:
                result = self._send_message(
                    messaging.Message(
                        token=token,
                        data=data,
                        android=messaging.AndroidConfig(priority="normal", ttl=timedelta(hours=6)),
                    ),
                    transition,
                    target="token",
                )
                if result:
                    results.append(result)
            return results

        result = self._send_message(
            messaging.Message(
                topic=self.topic,
                data=data,
                android=messaging.AndroidConfig(priority="normal", ttl=timedelta(hours=6)),
            )
            if self.topic
            else None,
            transition,
            target=f"topic:{self.topic}",
        )
        return [result] if result else []

    def _send_message(
        self,
        message: Optional[messaging.Message],
        transition: Transition,
        target: str,
    ) -> Optional[str]:
        if message is None:
            return None
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
            return result
        except Exception:
            LOGGER.exception(
                "fcm_send_failed",
                extra=extra(alarm=transition.alarm, event_type=transition.event_type, target=target),
            )
            return None
