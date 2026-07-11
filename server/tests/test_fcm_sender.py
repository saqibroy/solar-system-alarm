from wapda_alarm.config import AlarmDefinition
from wapda_alarm.fcm import FcmAlarmSender
from wapda_alarm.state import Transition


def test_send_transition_to_topic_builds_firebase_message(monkeypatch):
    sent = []

    def fake_send(message):
        sent.append(message)
        return "message-id"

    monkeypatch.setattr("wapda_alarm.fcm.messaging.send", fake_send)
    sender = FcmAlarmSender.__new__(FcmAlarmSender)
    sender.topic = "wapda-alarm-alerts"

    results = sender.send_transition(
        Transition(
            alarm="LINE_FAIL",
            event_type="alarm_start",
            active_ids=("1",),
            timestamp="2026-07-12T00:00:00+00:00",
        ),
        AlarmDefinition("LINE_FAIL", ("LINE_FAIL",), "critical"),
        [],
        message="Grid is out",
    )

    assert results == ["message-id"]
    assert len(sent) == 1
    assert sent[0].topic == "wapda-alarm-alerts"
    assert sent[0].data["message"] == "Grid is out"
