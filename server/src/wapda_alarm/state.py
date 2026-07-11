from __future__ import annotations

import json
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Transition:
    alarm: str
    event_type: str
    active_ids: tuple[str, ...]
    timestamp: str


class AlarmStateStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.data = self._load()

    def apply(self, active_by_alarm: dict[str, set[str]]) -> list[Transition]:
        transitions: list[Transition] = []
        alarms = self.data.setdefault("alarms", {})
        now = datetime.now(timezone.utc).isoformat()

        for alarm_name, active_ids in active_by_alarm.items():
            entry = alarms.setdefault(alarm_name, {"active": False, "active_ids": []})
            was_active = bool(entry.get("active", False))
            is_active = bool(active_ids)
            sorted_ids = sorted(active_ids)

            if is_active and not was_active:
                transitions.append(Transition(alarm_name, "alarm_start", tuple(sorted_ids), now))
            elif was_active and not is_active:
                transitions.append(Transition(alarm_name, "alarm_stop", tuple(), now))

            entry["active"] = is_active
            entry["active_ids"] = sorted_ids
            entry["updated_at"] = now

        self.data["updated_at"] = now
        self.save()
        return transitions

    def active_alarms(self) -> dict[str, list[str]]:
        return {
            name: list(entry.get("active_ids", []))
            for name, entry in self.data.get("alarms", {}).items()
            if entry.get("active")
        }

    def get_meta(self, key: str, default: Any = None) -> Any:
        return self.data.get("meta", {}).get(key, default)

    def set_meta(self, key: str, value: Any) -> None:
        self.data.setdefault("meta", {})[key] = value
        self.save()

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", dir=self.path.parent, delete=False) as tmp:
            json.dump(self.data, tmp, indent=2, sort_keys=True)
            tmp.write("\n")
            tmp_path = Path(tmp.name)
        tmp_path.replace(self.path)

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"alarms": {}}
        with self.path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
