from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Any, Optional

from flask import Flask, jsonify, request

from .config import AlarmDefinition
from .fcm import FcmAlarmSender
from .registry import TokenRegistry
from .state import AlarmStateStore, Transition


@dataclass
class RuntimeStatus:
    started_monotonic: float = field(default_factory=time.monotonic)
    last_poll_at: Optional[str] = None
    last_poll_ok: bool = False
    last_error: Optional[str] = None
    poll_count: int = 0


def create_app(
    status: RuntimeStatus,
    state_store: AlarmStateStore,
    token_registry: TokenRegistry,
    registration_secret: str,
    fcm_sender: Optional[FcmAlarmSender] = None,
    alarms: tuple[AlarmDefinition, ...] = (),
) -> Flask:
    app = Flask(__name__)
    alarm_by_name = {alarm.name: alarm for alarm in alarms}

    @app.get("/health")
    def health() -> Any:
        return jsonify(
            {
                "ok": status.last_error is None,
                "uptime_seconds": round(time.monotonic() - status.started_monotonic, 1),
                "last_poll_at": status.last_poll_at,
                "last_poll_ok": status.last_poll_ok,
                "last_error": status.last_error,
                "poll_count": status.poll_count,
                "active_alarms": state_store.active_alarms(),
                "registered_tokens": len(token_registry.tokens()),
            }
        )

    @app.post("/register")
    def register() -> Any:
        supplied = request.headers.get("X-Registration-Secret", "")
        auth = request.headers.get("Authorization", "")
        if auth.lower().startswith("bearer "):
            supplied = auth[7:].strip()
        if supplied != registration_secret:
            return jsonify({"ok": False, "error": "unauthorized"}), 401

        body = request.get_json(force=True, silent=True) or {}
        token = str(body.get("token", "")).strip()
        device_name = str(body.get("device_name", "android-phone")).strip()
        if not token:
            return jsonify({"ok": False, "error": "missing token"}), 400
        token_registry.upsert(token, device_name=device_name)
        resent = _send_current_active_alarms(token, state_store, fcm_sender, alarm_by_name)
        return jsonify({"ok": True, "registered_tokens": len(token_registry.tokens()), "resent_active_alarms": resent})

    return app


def _send_current_active_alarms(
    token: str,
    state_store: AlarmStateStore,
    fcm_sender: Optional[FcmAlarmSender],
    alarm_by_name: dict[str, AlarmDefinition],
) -> int:
    if fcm_sender is None:
        return 0
    sent = 0
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    for alarm_name, active_ids in state_store.active_alarms().items():
        alarm = alarm_by_name.get(alarm_name)
        if alarm is None:
            continue
        fcm_sender.send_transition(
            Transition(
                alarm=alarm_name,
                event_type="alarm_start",
                active_ids=tuple(active_ids),
                timestamp=now,
            ),
            alarm,
            [token],
        )
        sent += 1
    return sent


def start_health_server(app: Flask, host: str, port: int) -> threading.Thread:
    thread = threading.Thread(
        target=lambda: app.run(host=host, port=port, debug=False, use_reloader=False),
        name="health-http",
        daemon=True,
    )
    thread.start()
    return thread
