#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/solar-system-alarm}"
REPO_URL="${REPO_URL:-https://github.com/saqibroy/solar-system-alarm.git}"
BRANCH="${BRANCH:-main}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-solar-system-alarm}"

if [ ! -d "$APP_DIR/.git" ]; then
  sudo rm -rf "$APP_DIR"
  sudo mkdir -p "$APP_DIR"
  sudo chown "$(id -u):$(id -g)" "$APP_DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
fi

cd "$APP_DIR"
git fetch origin "$BRANCH"
local_sha="$(git rev-parse HEAD)"
remote_sha="$(git rev-parse "origin/$BRANCH")"

if [ "$local_sha" = "$remote_sha" ] && [ "${FORCE_DEPLOY:-0}" != "1" ]; then
  echo "solar-system-alarm already at $local_sha"
  exit 0
fi

git reset --hard "$remote_sha"

sudo mkdir -p /etc/wapda-alarm /var/lib/wapda-alarm

if [ ! -f /etc/wapda-alarm/wapda-alarm.env ]; then
  echo "Missing /etc/wapda-alarm/wapda-alarm.env" >&2
  exit 2
fi

if [ ! -f /etc/wapda-alarm/firebase-service-account.json ]; then
  echo "Missing /etc/wapda-alarm/firebase-service-account.json" >&2
  exit 2
fi

docker compose -p "$COMPOSE_PROJECT_NAME" up -d --build --remove-orphans
docker compose -p "$COMPOSE_PROJECT_NAME" ps
