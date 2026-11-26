from types import SimpleNamespace

import app
from speech_plan import plan_speech

# Each fake take is named for what it sounds like.
MISMATCH = {b'low': 25.0, b'lower': 60.0}


class FakeReferences:
    def __init__(self, has_reference=False):
        self.has_reference = has_reference
        self.saved = []
        self.discarded = 0

    def save(self, segment, pcm, rate):
        self.saved.append((segment.text, pcm))

    def discard(self, segment):
        self.discarded += 1
        self.has_reference = False


def run(monkeypatch, takes, has_reference=False):
    segment = plan_speech('“Hold still.”', {'id': 'elara', 'gender': 'Woman', 'personality': 'Calm.'})[0]
    references = FakeReferences(has_reference)
    attempts = []
    queue = list(takes)

    def generate(model, seg, attempt):
        attempts.append((attempt, references.has_reference))
        return queue.pop(0), {'text': seg.text}, not references.has_reference

    monkeypatch.setattr(app, 'generate', generate)
    monkeypatch.setattr(app, 'plausible_duration', lambda text, seconds, language: seconds > 0)
    monkeypatch.setattr(app, 'pitch_mismatch', lambda pcm, rate, gender: MISMATCH.get(pcm, 0.0))
    model = SimpleNamespace(sample_rate=1, voice_references=references)
    audio = app.best_take(model, segment)
    return audio, attempts, references


def test_a_wrong_gender_design_is_retried_and_only_the_matching_take_is_pinned(monkeypatch):
    audio, attempts, refs = run(monkeypatch, [b'low', b'high'])
    assert [a for a, _ in attempts] == [0, 1]
    assert audio == b'high' and refs.saved == [('Hold still.', b'high')]


def test_when_no_design_matches_the_closest_is_pinned_so_the_voice_never_changes(monkeypatch):
    audio, attempts, refs = run(monkeypatch, [b'lower', b'low', b'lower', b'lower'])
    assert len(attempts) == app.TAKE_ATTEMPTS
    assert audio == b'low' and refs.saved == [('Hold still.', b'low')]


def test_a_cut_off_design_is_never_pinned(monkeypatch):
    audio, _, refs = run(monkeypatch, [b''] + [b'high'])
    assert audio == b'high' and refs.saved == [('Hold still.', b'high')]


def test_a_cut_off_continuation_is_retried_without_redesigning(monkeypatch):
    audio, attempts, refs = run(monkeypatch, [b'', b'ok'], has_reference=True)
    assert attempts == [(0, True), (1, True)]
    assert audio == b'ok' and refs.saved == [] and refs.discarded == 0


def test_a_reference_that_only_yields_cut_off_lines_is_replaced(monkeypatch):
    takes = [b''] * app.TAKE_ATTEMPTS + [b'high']
    audio, attempts, refs = run(monkeypatch, takes, has_reference=True)
    assert refs.discarded == 1
    assert attempts[-1] == (0, False)
    assert audio == b'high' and refs.saved == [('Hold still.', b'high')]


def test_a_reference_that_cannot_be_discarded_is_not_retried_forever(monkeypatch):
    segment = plan_speech('“Hold still.”', {'id': 'elara', 'gender': 'Woman', 'personality': 'Calm.'})[0]
    calls = []
    monkeypatch.setattr(app, 'generate', lambda model, seg, attempt: calls.append(attempt) or (b'', {'text': seg.text}, False))
    model = SimpleNamespace(sample_rate=1, voice_references=SimpleNamespace(discard=lambda seg: None))
    assert app.best_take(model, segment) == b''
    assert len(calls) == 2 * app.TAKE_ATTEMPTS
