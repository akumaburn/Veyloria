# Ming speech adapter

Veyloria's FastAPI/Uvicorn service plans and synthesizes speech with
`inclusionAI/Ming-3.0-tts-0.5B`. It uses Voice Design for an initial voice and
cached acoustic references for continuation. The character profile and
Personality guide a concise voice instruction; speech scripts contain only
spoken words and supported tags.

From the repository root:

```bash
./scripts/run-tts.sh                 # Native development; requires uv, Git and CUDA
# Or deploy the container with NVIDIA Container Toolkit:
docker compose up -d --build tts
```

Missing model files download at startup. Runtime files and references persist
outside the source tree under `.runtime/tts` natively or the `tts-models` Docker
volume. The process loads the model before reporting readiness; Python edits
require a restart. Browsers connect through the frontend's same-origin `/tts`
proxy rather than directly to the worker.

See [voice playback](../../docs/voice-playback.md) for exact endpoints, environment
variables, prompt contracts, segmentation, playback buffering, lifecycle and
licensing. Adapter tests run without generating model audio:

```bash
PYTHONPATH=services/tts .runtime/tts/venv/bin/python -m pytest services/tts/tests -q
```

Copyright 2024-2026 Amir Farhad Eslampanah. [Apache-2.0](../../LICENSE).
Upstream Ming and dependencies retain their own licenses and notices.
