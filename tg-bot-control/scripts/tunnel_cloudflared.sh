#!/usr/bin/env bash
# Expose local API (Termux / home server) via Cloudflare Tunnel (HTTPS+WSS).
# Install: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/
# Usage: ./tunnel_cloudflared.sh   (API must run on :8000)
set -euo pipefail
exec cloudflared tunnel --url http://localhost:8000
