#!/usr/bin/env bash
# Native development runtime: uses the host GPU without Docker GPU plumbing.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TTS_RUNTIME_DIR="${TTS_RUNTIME_DIR:-$PROJECT_ROOT/.runtime/tts}"
MING_COMMIT=c88c023ea6ccc08fd1e7a1d289cb6246c21deff1
command -v uv >/dev/null || { echo 'Ming startup requires uv (https://docs.astral.sh/uv/).' >&2; exit 1; }
mkdir -p "$TTS_RUNTIME_DIR"
if [[ ! -d "$TTS_RUNTIME_DIR/ming/.git" ]]; then
  git clone https://github.com/inclusionAI/Ming-3.0-tts.git "$TTS_RUNTIME_DIR/ming"
fi
if [[ "$(git -C "$TTS_RUNTIME_DIR/ming" rev-parse HEAD)" != "$MING_COMMIT" ]]; then
  git -C "$TTS_RUNTIME_DIR/ming" fetch origin "$MING_COMMIT"
  git -C "$TTS_RUNTIME_DIR/ming" checkout --detach "$MING_COMMIT"
fi
if [[ ! -x "$TTS_RUNTIME_DIR/venv/bin/python" ]]; then
  uv venv --python 3.11 "$TTS_RUNTIME_DIR/venv"
fi
# Keep Ming's inference dependencies, using CUDA 12.8 for RTX 50-series support.
sed '/^torch==/d; /^torchaudio==/d' "$TTS_RUNTIME_DIR/ming/requirements.txt" > "$TTS_RUNTIME_DIR/requirements.txt"
uv pip install --python "$TTS_RUNTIME_DIR/venv/bin/python" --index-url https://download.pytorch.org/whl/cu128 torch==2.7.1 torchaudio==2.7.1
uv pip install --python "$TTS_RUNTIME_DIR/venv/bin/python" -r "$TTS_RUNTIME_DIR/requirements.txt" fastapi==0.115.12 'uvicorn[standard]==0.34.2'
export PYTHONPATH="$TTS_RUNTIME_DIR/ming${PYTHONPATH:+:$PYTHONPATH}"
export HF_HOME="${HF_HOME:-$TTS_RUNTIME_DIR/huggingface}"
export MING_DEVICE="${MING_DEVICE:-cuda:0}"
export TTS_ALLOWED_ORIGINS="${TTS_ALLOWED_ORIGINS:-http://localhost:3000,http://rpg.local:3000}"
cd "$PROJECT_ROOT/services/tts"
exec "$TTS_RUNTIME_DIR/venv/bin/python" -m uvicorn app:app --host 127.0.0.1 --port "${TTS_PORT:-9797}" --workers 1 --ws-max-size 131072
