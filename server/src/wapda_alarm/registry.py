from __future__ import annotations

import json
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional


class TokenRegistry:
    def __init__(self, path: Path, seed_tokens: tuple[str, ...] = ()) -> None:
        self.path = path
        self.data = self._load()
        for token in seed_tokens:
            self.upsert(token, device_name="env-configured", save=False)
        self.save()

    def upsert(self, token: str, device_name: Optional[str] = None, save: bool = True) -> None:
        token = token.strip()
        if not token:
            raise ValueError("Empty FCM token")
        tokens = self.data.setdefault("tokens", {})
        tokens[token] = {
            "device_name": device_name or tokens.get(token, {}).get("device_name") or "android-phone",
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        if save:
            self.save()

    def tokens(self) -> list[str]:
        return sorted(self.data.get("tokens", {}).keys())

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", dir=self.path.parent, delete=False) as tmp:
            json.dump(self.data, tmp, indent=2, sort_keys=True)
            tmp.write("\n")
            tmp_path = Path(tmp.name)
        tmp_path.replace(self.path)

    def _load(self) -> dict:
        if not self.path.exists():
            return {"tokens": {}}
        with self.path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
