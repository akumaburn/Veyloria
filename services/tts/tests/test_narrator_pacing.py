import base64
from types import SimpleNamespace

import pytest

torch = pytest.importorskip('torch')

from audio_utils import NARRATOR_PAUSE_SECONDS, pace_narration
from speech_plan import plan_speech
from app import synthesize


def tone(rate=16000):
    time = torch.arange(rate) / rate
    return (.3 * torch.sin(2 * torch.pi * 220 * time)).unsqueeze(0)


def test_narration_preserves_every_original_sample_and_only_adds_a_pause():
    rate = 16000
    original = (tone().flatten() * 32767).to(torch.int16).numpy().astype('<i2').tobytes()
    output = pace_narration(original, rate)
    assert output[:len(original)] == original
    assert output[len(original):] == b'\0\0' * round(rate * NARRATOR_PAUSE_SECONDS)
    assert pace_narration(b'', rate) == b''


def test_only_narrator_is_paced_and_reference_audio_is_not_slowed_twice():
    calls = []
    references = SimpleNamespace(
        options=lambda segment: ({'task': 'VOICE_DESIGN', 'text': segment.text}, True),
        save=lambda segment, pcm, rate: calls.append((segment.speaker, pcm)),
    )
    model = SimpleNamespace(device='cpu', sample_rate=16000, voice_references=references,
                            synthesize=lambda **kwargs: tone())
    narrator = plan_speech('The corridor is quiet.')[0]
    character = plan_speech('“Stay close.”', {'id': 'elara', 'personality': 'Impulsive.'})[0]
    narrator_audio, narrator_duration = synthesize(model, narrator)
    character_audio, character_duration = synthesize(model, character)
    assert narrator_duration > character_duration
    assert calls[0][1] == calls[1][1]
    assert base64.b64decode(character_audio) == calls[1][1]
    assert len(base64.b64decode(narrator_audio)) > len(calls[0][1])
