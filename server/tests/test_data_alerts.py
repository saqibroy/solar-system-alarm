from datetime import datetime, timedelta, timezone

from wapda_alarm.config import DataAlertConfig
from wapda_alarm.data_alerts import evaluate_data_alerts
from wapda_alarm.shinemonitor_client import InverterSnapshot


CONFIG = DataAlertConfig(
    enabled=True,
    battery_low_percent=35,
    high_load_watts=1200,
    high_load_percent=70,
    stale_data_minutes=10,
    pv_loss_max_watts=20,
    pv_loss_day_start_hour=7,
    pv_loss_day_end_hour=18,
    local_timezone="Asia/Karachi",
    daily_summary_enabled=True,
    daily_summary_hour=21,
    status_push_interval_minutes=30,
)


def test_battery_low_and_high_load_require_grid_out():
    now = datetime(2026, 7, 12, 8, tzinfo=timezone.utc)
    snapshot = _snapshot(now, battery_capacity=25, ac_output_active_power=1300)

    clear = evaluate_data_alerts(snapshot, {"LINE_FAIL": set()}, CONFIG, now)
    grid_out = evaluate_data_alerts(snapshot, {"LINE_FAIL": {"1"}}, CONFIG, now)

    assert "BATTERY_LOW" not in clear.active
    assert "HIGH_LOAD" not in clear.active
    assert "BATTERY_LOW" in grid_out.active
    assert "HIGH_LOAD" in grid_out.active


def test_pv_loss_only_during_daytime_when_discharging():
    now = datetime(2026, 7, 12, 8, tzinfo=timezone.utc)
    snapshot = _snapshot(now, pv_input_power=0, battery_discharge_current=3.0)

    active = evaluate_data_alerts(snapshot, {"LINE_FAIL": set()}, CONFIG, now)

    assert "PV_LOSS" in active.active


def test_stale_data_when_snapshot_is_old():
    now = datetime(2026, 7, 12, 8, tzinfo=timezone.utc)
    snapshot = _snapshot(now - timedelta(minutes=12))

    active = evaluate_data_alerts(snapshot, {"LINE_FAIL": set()}, CONFIG, now)

    assert "STALE_DATA" in active.active


def _snapshot(timestamp: datetime, **overrides):
    values = {
        "timestamp": timestamp,
        "grid_voltage": 230.0,
        "pv_input_power": 800,
        "battery_capacity": 80,
        "battery_discharge_current": 0.0,
        "ac_output_active_power": 400,
        "output_load_percent": 25,
    }
    values.update(overrides)
    return InverterSnapshot(**values)
