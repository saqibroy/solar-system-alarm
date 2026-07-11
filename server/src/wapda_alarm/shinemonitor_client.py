from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Optional

from shinemonitor_api import ShineMonitorAPI
from shinemonitor_api import _protocol as shine_protocol
from shinemonitor_api.models import DeviceIdentifier

from .config import AlarmDefinition, WatcherConfig
from .logging_config import extra

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class DeviceTarget:
    pn: str
    sn: str
    devcode: int
    devaddr: int


class ShineMonitorWarningsClient:
    def __init__(self, config: WatcherConfig) -> None:
        self.config = config
        self.api = ShineMonitorAPI(
            base_url=config.base_url,
            suffix_context=config.suffix_context,
            company_key=config.company_key,
            timeout=15.0,
        )
        self.target: Optional[DeviceTarget] = None

    def close(self) -> None:
        self.api.close()

    def login(self) -> None:
        self.api.login(self.config.username, self.config.password)
        LOGGER.info("shinemonitor_login_ok")

    def ensure_target(self) -> DeviceTarget:
        if self.target is not None:
            return self.target

        if (
            self.config.datalogger_pn
            and self.config.device_sn
            and self.config.device_code is not None
            and self.config.device_addr is not None
        ):
            self.target = DeviceTarget(
                pn=self.config.datalogger_pn,
                sn=self.config.device_sn,
                devcode=self.config.device_code,
                devaddr=self.config.device_addr,
            )
            return self.target

        devices = self.api.get_devices()
        selected = self._select_device(devices)
        self.target = DeviceTarget(
            pn=selected.wifi_pin,
            sn=selected.serial_number,
            devcode=selected.device_code,
            devaddr=selected.device_address,
        )
        LOGGER.info("shinemonitor_device_selected", extra=extra(target=self.target))
        return self.target

    def fetch_warnings(self) -> list[dict[str, Any]]:
        target = self.ensure_target()
        base_action = (
            "&action=queryDeviceWarning"
            f"&pn={target.pn}&devcode={target.devcode}&devaddr={target.devaddr}&sn={target.sn}"
            "&page=0&pagesize=50"
            f"{self.config.suffix_context}"
        )
        url = shine_protocol.authed_url(self.api._config, self.api._require_auth(), base_action)
        try:
            payload = self.api._get_json(url)
            checked = shine_protocol.check_response(payload)
        except shine_protocol.ShineMonitorError as exc:
            if exc.err == 0x0108:
                return []
            raise
        warnings = checked.get("dat", {}).get("warning", [])
        if not isinstance(warnings, list):
            LOGGER.warning("shinemonitor_warning_shape_unexpected", extra=extra(payload=checked))
            return []
        return [item for item in warnings if isinstance(item, dict)]

    def _select_device(self, devices: list[DeviceIdentifier]) -> DeviceIdentifier:
        if not devices:
            raise RuntimeError("ShineMonitor account returned no devices")

        matches = devices
        if self.config.datalogger_pn:
            matches = [d for d in matches if d.wifi_pin == self.config.datalogger_pn]
        if self.config.device_sn:
            matches = [d for d in matches if d.serial_number == self.config.device_sn]

        if len(matches) != 1:
            summary = [
                {
                    "pn": d.wifi_pin,
                    "sn": d.serial_number,
                    "devcode": d.device_code,
                    "devaddr": d.device_address,
                    "alias": d.device_alias,
                }
                for d in devices
            ]
            raise RuntimeError(
                "Could not uniquely select ShineMonitor device; set "
                "SHINEMONITOR_DATALOGGER_PN, SHINEMONITOR_DEVICE_SN, "
                "SHINEMONITOR_DEVICE_CODE and SHINEMONITOR_DEVICE_ADDR. "
                f"Available devices: {summary}"
            )
        return matches[0]


def active_warning_ids(
    warnings: list[dict[str, Any]], alarm_definitions: tuple[AlarmDefinition, ...]
) -> dict[str, set[str]]:
    active_by_alarm = {definition.name: set() for definition in alarm_definitions}
    for warning in warnings:
        if warning.get("cts"):
            continue
        text = _warning_text(warning)
        for definition in alarm_definitions:
            if any(keyword.lower() in text for keyword in definition.keywords):
                active_by_alarm[definition.name].add(str(warning.get("id") or _fallback_id(warning)))
    return active_by_alarm


def _warning_text(warning: dict[str, Any]) -> str:
    parts = [
        warning.get("desc"),
        warning.get("code"),
        warning.get("alias"),
        warning.get("status"),
        warning.get("level"),
    ]
    return " ".join(str(part) for part in parts if part is not None).lower()


def _fallback_id(warning: dict[str, Any]) -> str:
    return "|".join(
        str(warning.get(key, ""))
        for key in ("pn", "devcode", "devaddr", "sn", "code", "desc", "gts")
    )
