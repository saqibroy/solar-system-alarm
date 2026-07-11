from wapda_alarm.config import AlarmDefinition
from wapda_alarm.shinemonitor_client import active_warning_ids


ALARMS = (
    AlarmDefinition("LINE_FAIL", ("LINE_FAIL", "Line Fail"), "critical"),
    AlarmDefinition("PV_LOSS", ("PV Loss", "PV_LOSS"), "warning"),
)


def test_active_warning_matches_keyword_without_cts():
    warnings = [{"id": "1", "desc": "LINE_FAIL", "code": "F0001", "gts": "2026-07-11 10:00:00"}]

    active = active_warning_ids(warnings, ALARMS)

    assert active["LINE_FAIL"] == {"1"}
    assert active["PV_LOSS"] == set()


def test_resolved_warning_with_cts_is_not_active():
    warnings = [{"id": "1", "desc": "LINE_FAIL", "cts": "2026-07-11 10:10:00"}]

    active = active_warning_ids(warnings, ALARMS)

    assert active["LINE_FAIL"] == set()


def test_pv_loss_matches_desc_case_insensitively():
    warnings = [{"id": "2", "desc": "pv input lost / PV_LOSS"}]

    active = active_warning_ids(warnings, ALARMS)

    assert active["PV_LOSS"] == {"2"}
