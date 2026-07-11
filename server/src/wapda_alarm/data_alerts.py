from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from .config import DataAlertConfig
from .shinemonitor_client import InverterSnapshot


@dataclass(frozen=True)
class AlertEvaluation:
    active: dict[str, set[str]]
    summary: str


def evaluate_data_alerts(
    snapshot: InverterSnapshot,
    active_warning_alarms: dict[str, set[str]],
    config: DataAlertConfig,
    now: datetime,
) -> AlertEvaluation:
    if not config.enabled:
        return AlertEvaluation(active={}, summary=_summary(snapshot, active_warning_alarms))

    active: dict[str, set[str]] = {}
    grid_out = bool(active_warning_alarms.get("LINE_FAIL")) or _grid_voltage_is_out(snapshot.grid_voltage)
    snapshot_key = snapshot.timestamp.isoformat()

    if grid_out and _lte(snapshot.battery_capacity, config.battery_low_percent):
        active["BATTERY_LOW"] = {f"battery<={config.battery_low_percent}|{snapshot_key}"}

    high_watts = _gte(snapshot.ac_output_active_power, config.high_load_watts)
    high_percent = _gte(snapshot.output_load_percent, config.high_load_percent)
    if grid_out and (high_watts or high_percent):
        watts = snapshot.ac_output_active_power if snapshot.ac_output_active_power is not None else "unknown"
        percent = snapshot.output_load_percent if snapshot.output_load_percent is not None else "unknown"
        active["HIGH_LOAD"] = {f"load={watts}w/{percent}%|{snapshot_key}"}

    if _is_daytime(now, config) and _lte(snapshot.pv_input_power, config.pv_loss_max_watts):
        battery_is_discharging = _gte(snapshot.battery_discharge_current, 0.5)
        if grid_out or battery_is_discharging:
            active["PV_LOSS"] = {f"pv<={config.pv_loss_max_watts}w|{snapshot_key}"}

    age_seconds = _age_seconds(snapshot.timestamp, now)
    if age_seconds is not None and age_seconds > config.stale_data_minutes * 60:
        active["STALE_DATA"] = {f"last_data_age={int(age_seconds)}s|{snapshot_key}"}

    return AlertEvaluation(active=active, summary=_summary(snapshot, active_warning_alarms))


def should_send_daily_summary(now: datetime, config: DataAlertConfig, last_date: str | None) -> bool:
    if not config.daily_summary_enabled:
        return False
    local_now = now.astimezone(ZoneInfo(config.local_timezone))
    return local_now.hour >= config.daily_summary_hour and local_now.date().isoformat() != last_date


def local_summary_date(now: datetime, config: DataAlertConfig) -> str:
    return now.astimezone(ZoneInfo(config.local_timezone)).date().isoformat()


def should_send_status_push(now: datetime, last_sent_at: str | None, interval_minutes: int) -> bool:
    if interval_minutes <= 0:
        return False
    if not last_sent_at:
        return True
    try:
        last = datetime.fromisoformat(last_sent_at)
    except ValueError:
        return True
    if last.tzinfo is None:
        last = last.replace(tzinfo=timezone.utc)
    return (now - last).total_seconds() >= interval_minutes * 60


def _summary(snapshot: InverterSnapshot, active_warning_alarms: dict[str, set[str]]) -> str:
    line_state = "out" if active_warning_alarms.get("LINE_FAIL") else "clear"
    battery = _fmt(snapshot.battery_capacity, "%")
    load = _fmt(snapshot.ac_output_active_power, "W")
    load_percent = _fmt(snapshot.output_load_percent, "%")
    pv = _fmt(snapshot.pv_input_power, "W")
    grid = _fmt(snapshot.grid_voltage, "V")
    return (
        f"LINE_FAIL {line_state}. Battery {battery}, load {load} ({load_percent}), "
        f"PV {pv}, grid {grid}. Last inverter data {snapshot.timestamp.isoformat()}."
    )


def _grid_voltage_is_out(value: float | None) -> bool:
    return value is not None and value < 80


def _lte(value: int | float | None, threshold: int | float) -> bool:
    return value is not None and value <= threshold


def _gte(value: int | float | None, threshold: int | float) -> bool:
    return value is not None and value >= threshold


def _is_daytime(now: datetime, config: DataAlertConfig) -> bool:
    local_hour = now.astimezone(ZoneInfo(config.local_timezone)).hour
    return config.pv_loss_day_start_hour <= local_hour < config.pv_loss_day_end_hour


def _age_seconds(timestamp: datetime, now: datetime) -> float | None:
    if timestamp.tzinfo is None:
        timestamp = timestamp.replace(tzinfo=timezone.utc)
    return (now - timestamp.astimezone(timezone.utc)).total_seconds()


def _fmt(value: int | float | None, unit: str) -> str:
    if value is None:
        return "unknown"
    return f"{value}{unit}"
