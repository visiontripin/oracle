#!/usr/bin/env bash
# Run the Admin API + bot core (SQLite defaults, no Docker needed).
set -euo pipefail
cd "$(dirname "$0")/../server"

if [ ! -f .env ]; then
  echo "No server/.env found — copying from ../.env.example. EDIT IT (SECRET_KEY, BOT_TOKEN, ADMIN_PASSWORD)!"
  cp ../.env.example .env
fi

python3 -m pip install -r requirements.txt
python3 -m pytest -q
exec python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000
