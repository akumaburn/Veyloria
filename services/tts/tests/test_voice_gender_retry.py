from types import SimpleNamespace

import app
from speech_plan import plan_speech


class FakeReferences:
    def __init__(self):
        self.saved = []

    def save(self, segment, pcm, rate):
        self.saved.append((segment.text, pcm))


def run(monkeypatch, takes, designed=True):
    segment = plan_speech('“Hold still.”', {'id': 'elara', 'gender': 'Woman', 'personality': 'Calm.'})[0]
    attempts = []

    def generate(model, seg, attempt):
        attempts.append(attempt)
        return takes[attempt], {'text': seg.text}, designed

    pitch = {b'low': 130.0, b'high': 210.0}
    monkeypatch.setattr(app, 'generate', generate)
    monkeypatch.setattr('audio_utils.median_pitch', lambda pcm, rate: pitch[pcm])
    model = SimpleNamespace(sample_rate=24000, voice_references=FakeReferences())
    app.synthesize(model, segment)
    return attempts, model.voice_references.saved


def test_a_wrong_gender_design_is_retried_and_only_the_matching_take_is_pinned(monkeypatch):
    attempts, saved = run(monkeypatch, [b'low', b'high', b'high'])
    assert attempts == [0, 1]
    assert saved == [('Hold still.', b'high')]


def test_a_voice_that_never_matches_plays_but_is_not_pinned(monkeypatch):
    attempts, saved = run(monkeypatch, [b'low'] * app.DESIGN_ATTEMPTS)
    assert attempts == list(range(app.DESIGN_ATTEMPTS))
    assert saved == []


def test_reference_continuations_are_never_regenerated(monkeypatch):
    attempts, saved = run(monkeypatch, [b'low'], designed=False)
    assert attempts == [0] and saved == []
