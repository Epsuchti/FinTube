#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="${VENV_DIR:-${ROOT_DIR}/.dev/yt-dlp}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

"${PYTHON_BIN}" -m venv "${VENV_DIR}"
"${VENV_DIR}/bin/python" -m pip install --upgrade pip
"${VENV_DIR}/bin/python" -m pip install --upgrade yt-dlp yt-dlp-ejs 'bgutil-ytdlp-pot-provider==2.0.0'

printf 'Set yt_dlp_path to %s/bin/yt-dlp\n' "${VENV_DIR}"
