from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv


DEFAULT_ALARMS = [
    {
        "name": "LINE_FAIL",
        "keywords": ["LINE_FAIL", "Line Fail", "Line fail", "Grid fail", "Mains fail"],
        "severity": "critical",
    },
    {
        "name": "PV_LOSS",
        "keywords": ["PV Loss", "PV_LOSS", "PV input lost", "Solar loss", "No PV"],
        "severity": "warning",
    },
]


@dataclass(frozen=True)
class AlarmDefinition:
    name: str
    keywords: tuple[str, ...]
    severity: str


@dataclass(frozen=True)
class WatcherConfig:
    username: str
    password: str
    company_key: str
    base_url: str
    suffix_context: str
    datalogger_pn: Optional[str]
    device_sn: Optional[str]
    device_code: Optional[int]
    device_addr: Optional[int]
    plant_id: Optional[str]
    poll_interval_seconds: int
    state_file: Path
    token_registry_file: Path
    firebase_credentials: Path
    fcm_device_tokens: tuple[str, ...]
    fcm_topic: Optional[str]
    registration_secret: str
    health_host: str
    health_port: int
    alarms: tuple[AlarmDefinition, ...]


def load_config(env_file: Optional[str] = None) -> WatcherConfig:
    if env_file:
        load_dotenv(env_file)
    else:
        load_dotenv()

    return WatcherConfig(
        username=_required("SHINEMONITOR_USERNAME"),
        password=_required("SHINEMONITOR_PASSWORD"),
        company_key=_required("SHINEMONITOR_COMPANY_KEY"),
        base_url=os.getenv("SHINEMONITOR_BASE_URL", "http://api.shinemonitor.com/public/"),
        suffix_context=os.getenv(
            "SHINEMONITOR_SUFFIX_CONTEXT",
            "&i18n=en_US&lang=en_US&source=1&_app_client_=android"
            "&_app_id_=wifiapp.volfw.watchpower&_app_version_=1.0.6.3",
        ),
        datalogger_pn=_optional("SHINEMONITOR_DATALOGGER_PN"),
        device_sn=_optional("SHINEMONITOR_DEVICE_SN"),
        device_code=_optional_int("SHINEMONITOR_DEVICE_CODE"),
        device_addr=_optional_int("SHINEMONITOR_DEVICE_ADDR"),
        plant_id=_optional("SHINEMONITOR_PLANT_ID"),
        poll_interval_seconds=int(os.getenv("POLL_INTERVAL_SECONDS", "90")),
        state_file=Path(os.getenv("STATE_FILE", "./state.json")),
        token_registry_file=Path(os.getenv("TOKEN_REGISTRY_FILE", "./tokens.json")),
        firebase_credentials=Path(_required("FIREBASE_CREDENTIALS")),
        fcm_device_tokens=tuple(_split_csv(os.getenv("FCM_DEVICE_TOKENS", ""))),
        fcm_topic=_optional("FCM_TOPIC"),
        registration_secret=_required("REGISTRATION_SECRET"),
        health_host=os.getenv("HEALTH_HOST", "0.0.0.0"),
        health_port=int(os.getenv("HEALTH_PORT", "8088")),
        alarms=_load_alarms(),
    )


def _load_alarms() -> tuple[AlarmDefinition, ...]:
    raw = os.getenv("ALARM_DEFINITIONS_JSON")
    entries = json.loads(raw) if raw else DEFAULT_ALARMS
    alarms: list[AlarmDefinition] = []
    for entry in entries:
        name = str(entry["name"]).strip()
        keywords = tuple(str(k).strip() for k in entry.get("keywords", []) if str(k).strip())
        if not name or not keywords:
            raise ValueError("Each alarm definition needs a name and at least one keyword")
        alarms.append(
            AlarmDefinition(
                name=name,
                keywords=keywords,
                severity=str(entry.get("severity", "critical")).strip() or "critical",
            )
        )
    return tuple(alarms)


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ValueError(f"Missing required environment variable: {name}")
    return value


def _optional(name: str) -> Optional[str]:
    value = os.getenv(name, "").strip()
    return value or None


def _optional_int(name: str) -> Optional[int]:
    value = _optional(name)
    return int(value) if value is not None else None


def _split_csv(raw: str) -> list[str]:
    return [item.strip() for item in raw.split(",") if item.strip()]
