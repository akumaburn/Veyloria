"""Sentence continuity across formatting, chunking and model inference."""
import base64
from dataclasses import replace
from types import SimpleNamespace

import pytest

import app as service
from speech_plan import plan_speech, playback_groups
from voice_references import VoiceReferences

PROFILE = {'id': 'elara', 'personality': 'Impulsive and loyal.', 'gender': 'Woman'}


@pytest.mark.parametrize('text, expected, speaker', [
    ('I *shift my weight* toward the **door**.', 'I shift my weight toward the door.', 'character'),
    ('The **heavy** door *creaks* open.', 'The heavy door creaks open.', 'narrator'),
    ('The road bends\naround the hill.', 'The road bends around the hill.', 'narrator'),
    ('Dr. Mira waits... then enters.', 'Dr. Mira waits... then enters.', 'narrator'),
    ('The U.S. envoy checks version 3.14 before leaving.', 'The U.S. envoy checks version three point one four before leaving.', 'narrator'),
])
def test_formatting_wrapping_and_nonterminal_punctuation_stay_continuous(text, expected, speaker):
    segments = plan_speech(text, PROFILE)
    assert len(segments) == 1
    assert segments[0].text == expected
    assert segments[0].speaker == speaker


def test_normal_long_sentence_is_one_utterance():
    text = 'The traveler walks ' + 'along the quiet winding road ' * 12 + 'toward the village.'
    assert 200 < len(text) < 600
    segments = plan_speech(text)
    assert [segment.text for segment in segments] == [text]
    assert segments[0].pause_after


def test_exceptionally_long_sentence_continues_at_clauses_and_plays_as_one_buffer(monkeypatch):
    text = ', '.join(['The traveler follows the winding road through the quiet valley'] * 16) + '.'
    segments = plan_speech(text)
    assert len(segments) > 1
    assert ' '.join(s.text for s in segments) == text
    assert all(len(s.text) <= 600 for s in segments)
    assert all(s.text.endswith(',') and not s.pause_after for s in segments[:-1])
    assert segments[-1].pause_after
    groups = playback_groups(segments)
    assert len(groups) == 1
    calls = []
    def synthesize(model, segment):
        calls.append(segment)
        return base64.b64encode(b'\x01\x02').decode(), .1
    monkeypatch.setattr(service, 'synthesize', synthesize)
    audio, duration = service.synthesize_group(None, groups[0])
    assert base64.b64decode(audio) == b'\x01\x02' * len(segments)
    assert duration == pytest.approx(.1 * len(segments))
    assert calls == segments


def test_partial_narration_before_dialogue_does_not_get_a_sentence_pause():
    segments = plan_speech('She whispers, “Stay close.”', PROFILE)
    assert [s.pause_after for s in segments] == [False, True]
    assert len(playback_groups(segments)) == 2  # Preserve the change of speaker.


def test_both_design_and_reference_bypass_mings_internal_splitter(tmp_path):
    store = VoiceReferences(tmp_path, 'model')
    segment = plan_speech('“Stay close.”', PROFILE)[0]
    options, designed = store.options(segment)
    assert designed and options['raw_text'] is True
    store.save(segment, b'\x00\x10' * 48000, 24000)
    options, designed = store.options(segment)
    assert not designed and options['raw_text'] is True


def test_continuations_have_no_added_narrator_pause():
    torch = pytest.importorskip('torch')
    options_seen = []
    refs = SimpleNamespace(options=lambda s: ({'text': s.text}, False))
    def inference(**options):
        options_seen.append(options)
        return torch.ones(16000) * .2
    model = SimpleNamespace(device='cpu', sample_rate=16000, voice_references=refs, synthesize=inference)
    sentence = plan_speech('The road stretches ahead.')[0]
    _, terminal_duration = service.synthesize(model, sentence)
    _, continuation_duration = service.synthesize(model, replace(sentence, pause_after=False))
    assert terminal_duration - continuation_duration == pytest.approx(.25)
    long_sentence = replace(sentence, text='A quiet road ' * 30 + 'ends here.')
    service.synthesize(model, long_sentence)
    assert options_seen[-1]['max_decode_steps'] >= len(long_sentence.text) * 4
