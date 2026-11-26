from dataclasses import replace
import struct

import pytest

from speech_plan import plan_speech
from voice_references import VoiceReferences


@pytest.mark.parametrize('profile', [
    None,
    {'name': 'Unknown'},
    {'id': 'elara', 'personality': 'Impulsive and fiercely loyal.', 'gender': 'Woman'},
    {'id': 'amir', 'personality': 'Measured and thoughtful.', 'gender': 'Man'},
    {'id': 'riven', 'personality': 'Soft-spoken and curious.', 'gender': 'nonbinary'},
])
def test_every_voice_design_has_the_exact_smooth_prefix(tmp_path, profile):
    segments = plan_speech('The corridor is quiet. “Stay close to the lantern.”', profile)
    store = VoiceReferences(tmp_path, 'revision')
    for segment in segments:
        options, designed = store.options(segment)
        assert designed
        assert options['controls']['style'].startswith('smooth voice, ')
        assert not options['text'].startswith('smooth voice,')
        if profile and profile.get('personality') and segment.speaker == 'character':
            description = options['controls']['style'].removeprefix('smooth voice, female voice. ')
            assert len(description.split('. ')) == 2


def test_old_reference_does_not_bypass_the_updated_design_prefix(tmp_path):
    segment = plan_speech('The portal is stable. Stay close to the lantern.')[0]
    store = VoiceReferences(tmp_path, 'revision')
    old = replace(segment, style=segment.style.removeprefix('smooth voice, '))
    store.save(old, struct.pack('<h', 1000) * 48000, 24000)
    assert store.get(old) is not None
    assert store.get(segment) is None
    options, designed = store.options(segment)
    assert designed
    assert options['controls']['style'].startswith('smooth voice, ')


def test_expressive_narrator_replaces_monotone_reference_without_changing_character_voice(tmp_path):
    narrator = plan_speech('A light glimmers beyond the archway.')[0]
    old_narrator = replace(narrator, style='smooth voice, A calm adult male narrator with a warm low voice and clear diction. Speak slowly at a measured audiobook pace, with gentle pauses between sentences and room for the scene to settle.')
    store = VoiceReferences(tmp_path, 'revision')
    pcm = struct.pack('<h', 1000) * 48000
    store.save(old_narrator, pcm, 24000)
    character = plan_speech('“Stay close.”', {'id': 'elara', 'personality': 'Impulsive.'})[0]
    store.save(character, pcm, 24000)
    assert store.options(narrator)[1] is True
    assert store.options(character)[1] is False
    assert 'naturally varied sentence stress' in narrator.style
    assert 'varied sentence stress' in narrator.style
    assert 'measured audiobook pace' in narrator.style



def test_narrator_design_asks_for_a_clean_modern_recording():
    narrator = plan_speech('A light glimmers beyond the archway.')[0]
    assert narrator.style.startswith('smooth voice, ')
    assert 'clean studio clarity' in narrator.style
    assert 'crisp articulation' in narrator.style
    assert 'measured audiobook pace' in narrator.style
