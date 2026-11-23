"""Local Ming Voice Design adapter. One worker owns one model/GPU."""
import asyncio
import base64
from contextlib import asynccontextmanager
import logging
import os
from pathlib import Path
from dataclasses import replace

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, ValidationError

from speech_plan import plan_speech, playback_groups
from voice_design import SpeechLanguage
from audio_utils import trim_pcm_tail, pace_narration
from voice_references import VoiceReferences

log = logging.getLogger(__name__)
MODEL_ID = 'inclusionAI/Ming-3.0-tts-0.5B'


class Character(BaseModel):
    id: str = Field(default='', max_length=128)
    name: str = Field(default='', max_length=256)
    personality: str | None = Field(default='', max_length=16000)
    gender: str | None = Field(default=None, max_length=128)
    pronouns: str | None = Field(default=None, max_length=128)
    ageYears: int | None = Field(default=None, ge=0, le=100000)
    description: str | None = Field(default=None, max_length=16000)


class Request(BaseModel):
    action: str = 'synthesize'
    text: str = Field(min_length=1, max_length=32000)
    character: Character | None = None
    language_id: SpeechLanguage = 'en'


def load_model():
    from huggingface_hub import snapshot_download
    from ming_tts import MingTTS
    if os.getenv("MING_DEVICE", "cuda:0") == "cpu":
        import torch
        torch.set_num_threads(int(os.getenv("MING_CPU_THREADS", "4")))
    # Hub checks its persistent cache and resumes missing/incomplete downloads.
    path = snapshot_download(MODEL_ID, revision=os.getenv('MING_MODEL_REVISION', 'main'))
    return MingTTS(path, device=os.getenv('MING_DEVICE', 'cuda:0'), text_normalization=False, cache_prompts=False)


@asynccontextmanager
async def lifespan(app):
    app.state.model = await asyncio.to_thread(load_model)
    app.state.generation_lock = asyncio.Lock()
    model = app.state.model
    cache_dir = Path(os.getenv('MING_VOICE_CACHE_DIR',
                              str(Path(os.getenv('HF_HOME', '.cache/huggingface')) / 'voice-references')))
    model.voice_references = VoiceReferences(cache_dir, Path(model.local_path).name)
    yield
    del app.state.model


app = FastAPI(lifespan=lifespan)


@app.get('/health')
async def health():
    return {'ready': True, 'model': MODEL_ID, 'mode': 'VOICE_DESIGN', 'voice_continuity': 'persistent_reference'}


@app.post('/api/speech-plan')
async def speech_plan(request: Request):
    """Copy-ready prompt fields; preparation only, without loading another voice."""
    segments = plan_speech(request.text, request.character.model_dump() if request.character else None, request.language_id)
    return {'segments': [segment.prompt().as_fields() for segment in segments]}


@app.get('/api/voices')
async def voices():
    return {'success': True, 'data': [{
        'name': 'narrator', 'language': 'Multilingual', 'language_code': 'multi',
        'gender': 'male', 'description': 'Calm, clear male narrator (Ming Voice Design)',
    }]}


def synthesize(model, segment):
    import torch
    # Model inference and RNG access are serialized across connections. A stable
    # seed remains stable; saved acoustic references anchor follow-up identity.
    device_type = str(model.device).split(":", 1)[0]
    with torch.inference_mode(), torch.autocast(device_type=device_type, dtype=torch.bfloat16):
        torch.manual_seed(segment.seed)
        options, designed = model.voice_references.options(segment)
        # Longer intact sentences need enough decoder steps to finish; the
        # checkpoint's fixed default can truncate deliberately unhurried speech.
        options['max_decode_steps'] = min(2400, max(1000, len(options['text']) * 4))
        audio = model.synthesize(**options)
    pcm = (audio.detach().float().cpu().numpy().reshape(-1).clip(-1, 1) * 32767).astype('<i2')
    pcm_bytes = trim_pcm_tail(pcm.tobytes(), model.sample_rate, .12 if segment.pause_after else .02)
    if designed:
        model.voice_references.save(replace(segment, text=options['text']), pcm_bytes, model.sample_rate)
    if segment.speaker == 'narrator' and segment.pause_after:
        pcm_bytes = pace_narration(pcm_bytes, model.sample_rate)
    return base64.b64encode(pcm_bytes).decode(), len(pcm_bytes) / (2 * model.sample_rate)


def synthesize_group(model, segments):
    # Send one contiguous PCM buffer per sentence, even when inference needed
    # multiple bounded fragments. Never wait for the GPU midway through playback.
    chunks = [synthesize(model, segment) for segment in segments]
    pcm = b''.join(base64.b64decode(audio) for audio, _ in chunks)
    return base64.b64encode(pcm).decode(), sum(duration for _, duration in chunks)


@app.websocket('/ws/tts')
async def tts(websocket: WebSocket):
    # Same-origin proxy is the default. Explicit origins can be enabled for a
    # separately hosted frontend without opening this GPU endpoint to every site.
    origin = websocket.headers.get('origin')
    allowed = {v.strip() for v in os.getenv('TTS_ALLOWED_ORIGINS', '').split(',') if v.strip()}
    if origin and origin not in allowed and origin.split('://', 1)[-1] != websocket.headers.get('host'):
        await websocket.close(code=1008)
        return
    await websocket.accept()
    disconnected = asyncio.Event()
    try:
        payload = await asyncio.wait_for(websocket.receive_json(), timeout=30)
        request = Request.model_validate(payload)
        if request.action != 'synthesize':
            raise ValueError('Unsupported action')
        segments = plan_speech(request.text, request.character.model_dump() if request.character else None, request.language_id)
        groups = playback_groups(segments)
        model = app.state.model
        await websocket.send_json({'type': 'START', 'sample_rate': model.sample_rate,
                                   'total_chunks': len(groups)})

        async def watch_disconnect():
            try:
                while True:
                    await websocket.receive_text()
            except WebSocketDisconnect:
                disconnected.set()

        watcher = asyncio.create_task(watch_disconnect())
        try:
            for index, group in enumerate(groups):
                async with app.state.generation_lock:
                    if disconnected.is_set():
                        return
                    # Shield and await a running GPU job on cancellation: another
                    # connection must never enter this non-thread-safe model early.
                    job = asyncio.create_task(asyncio.to_thread(synthesize_group, model, group))
                    try:
                        audio, duration = await asyncio.shield(job)
                    except asyncio.CancelledError:
                        await job
                        raise
                if disconnected.is_set():
                    return
                await websocket.send_json({'type': 'CHUNK', 'chunk_index': index,
                                           'chunk_text': ' '.join(segment.text for segment in group), 'speaker': group[0].speaker,
                                           'audio_base64': audio, 'duration': duration})
            await websocket.send_json({'type': 'COMPLETE'})
        finally:
            watcher.cancel()
            await asyncio.gather(watcher, return_exceptions=True)
    except WebSocketDisconnect:
        pass
    except (ValueError, ValidationError):
        await websocket.send_json({'type': 'ERROR', 'error': 'Invalid speech request'})
    except Exception:
        log.exception('Speech generation failed')
        if not disconnected.is_set():
            await websocket.send_json({'type': 'ERROR', 'error': 'Speech generation failed; check TTS service logs'})
    finally:
        if not disconnected.is_set():
            await websocket.close()
