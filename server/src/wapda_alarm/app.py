from __future__ import annotations

import argparse
import logging

from .config import load_config
from .fcm import FcmAlarmSender
from .health import RuntimeStatus, create_app, start_health_server
from .logging_config import configure_logging
from .poller import AlarmPoller
from .registry import TokenRegistry
from .state import AlarmStateStore


def main() -> None:
    parser = argparse.ArgumentParser(description="Watch ShineMonitor alarms and send FCM pushes.")
    parser.add_argument("--env-file", default=None, help="Path to .env file")
    parser.add_argument("--once", action="store_true", help="Run one poll then exit")
    args = parser.parse_args()

    configure_logging()
    config = load_config(args.env_file)

    state_store = AlarmStateStore(config.state_file)
    token_registry = TokenRegistry(config.token_registry_file, config.fcm_device_tokens)
    status = RuntimeStatus()
    fcm_sender = FcmAlarmSender(config.firebase_credentials, topic=config.fcm_topic)
    health_app = create_app(
        status,
        state_store,
        token_registry,
        config.registration_secret,
        fcm_sender=fcm_sender,
        alarms=config.alarms,
    )
    start_health_server(health_app, config.health_host, config.health_port)

    poller = AlarmPoller(
        config=config,
        state_store=state_store,
        token_registry=token_registry,
        fcm_sender=fcm_sender,
        status=status,
    )

    logging.getLogger(__name__).info("wapda_alarm_watcher_started")
    if args.once:
        poller.client.login()
        poller.client.ensure_target()
        poller.poll_once()
    else:
        poller.run_forever()


if __name__ == "__main__":
    main()
