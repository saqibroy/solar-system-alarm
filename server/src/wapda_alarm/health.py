from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Any, Optional

from flask import Flask, jsonify, request

from .registry import TokenRegistry
from .state import AlarmStateStore


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
) -> Flask:
    app = Flask(__name__)

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
        return jsonify({"ok": True, "registered_tokens": len(token_registry.tokens())})

    return app


def start_health_server(app: Flask, host: str, port: int) -> threading.Thread:
    thread = threading.Thread(
        target=lambda: app.run(host=host, port=port, debug=False, use_reloader=False),
        name="health-http",
        daemon=True,
    )
    thread.start()
    return thread
