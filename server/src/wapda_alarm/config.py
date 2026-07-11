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

SYNTHETIC_ALARMS = [
    {"name": "PV_LOSS", "keywords": [], "severity": "warning"},
    {"name": "BATTERY_LOW", "keywords": [], "severity": "critical"},
    {"name": "HIGH_LOAD", "keywords": [], "severity": "warning"},
    {"name": "STALE_DATA", "keywords": [], "severity": "warning"},
    {"name": "DAILY_SUMMARY", "keywords": [], "severity": "info"},
]


@dataclass(frozen=True)
class AlarmDefinition:
    name: str
    keywords: tuple[str, ...]
    severity: str


@dataclass(frozen=True)
class DataAlertConfig:
    enabled: bool
    battery_low_percent: int
    high_load_watts: int
    high_load_percent: int
    stale_data_minutes: int
    pv_loss_max_watts: int
    pv_loss_day_start_hour: int
    pv_loss_day_end_hour: int
    local_timezone: str
    daily_summary_enabled: bool
    daily_summary_hour: int
    status_push_interval_minutes: int


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
    data_alerts: DataAlertConfig


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
        data_alerts=DataAlertConfig(
            enabled=_bool("ENABLE_DATA_ALERTS", True),
            battery_low_percent=int(os.getenv("BATTERY_LOW_PERCENT", "35")),
            high_load_watts=int(os.getenv("HIGH_LOAD_WATTS", "1200")),
            high_load_percent=int(os.getenv("HIGH_LOAD_PERCENT", "70")),
            stale_data_minutes=int(os.getenv("STALE_DATA_MINUTES", "10")),
            pv_loss_max_watts=int(os.getenv("PV_LOSS_MAX_WATTS", "20")),
            pv_loss_day_start_hour=int(os.getenv("PV_LOSS_DAY_START_HOUR", "7")),
            pv_loss_day_end_hour=int(os.getenv("PV_LOSS_DAY_END_HOUR", "18")),
            local_timezone=os.getenv("LOCAL_TIMEZONE", "Asia/Karachi"),
            daily_summary_enabled=_bool("ENABLE_DAILY_SUMMARY", True),
            daily_summary_hour=int(os.getenv("DAILY_SUMMARY_HOUR", "21")),
            status_push_interval_minutes=int(os.getenv("STATUS_PUSH_INTERVAL_MINUTES", "30")),
        ),
    )


def _load_alarms() -> tuple[AlarmDefinition, ...]:
    raw = os.getenv("ALARM_DEFINITIONS_JSON")
    entries = json.loads(raw) if raw else DEFAULT_ALARMS
    entries = list(entries)
    names = {str(entry.get("name", "")).strip() for entry in entries}
    for synthetic in SYNTHETIC_ALARMS:
        if synthetic["name"] not in names:
            entries.append(synthetic)
    alarms: list[AlarmDefinition] = []
    for entry in entries:
        name = str(entry["name"]).strip()
        keywords = tuple(str(k).strip() for k in entry.get("keywords", []) if str(k).strip())
        if not name:
            raise ValueError("Each alarm definition needs a name")
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


def _bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}
