from dataclasses import replace
import json
import struct
import wave

from speech_plan import plan_speech
from voice_references import VoiceReferences

PROFILE = {'id': 'elara', 'name': 'Elara', 'gender': 'Woman', 'ageYears': 24,
           'personality': 'Impulsive and fiercely loyal.'}
PCM = struct.pack('<h', 1000) * 48000
RATE = 24000


def character(text='The portal is stable. Stay close to me.'):
    return plan_speech(f'“{text}”', PROFILE)[0]


def test_first_clip_designs_voice_then_followups_clone_exact_audio_and_transcript(tmp_path):
    store = VoiceReferences(tmp_path, 'revision-one')
    first = character()
    options, designed = store.options(first)
    assert designed
    assert options == {'task': 'VOICE_DESIGN', 'text': first.text,
                       'controls': {'style': first.style}, 'language_id': 'en', 'use_zero_spk_emb': True, 'raw_text': True}
    store.save(first, PCM, RATE)
    second = character('This is a different line tomorrow.')
    options, designed = store.options(second)
    assert not designed
    assert options['task'] == 'TTS'
    assert options['text'] == second.text
    assert options['prompt_text'] == first.text
    assert options['use_spk_emb'] is True
    assert options['language_id'] == 'en'
    assert 'controls' not in options and 'use_zero_spk_emb' not in options
    with wave.open(options['prompt_wav_path'], 'rb') as audio:
        assert audio.getframerate() == RATE
        assert audio.readframes(audio.getnframes()) == PCM


def test_reference_survives_service_restart_and_does_not_change_on_later_lines(tmp_path):
    first = character()
    store = VoiceReferences(tmp_path, 'revision-one')
    store.save(first, PCM, RATE)
    restarted = VoiceReferences(tmp_path, 'revision-one')
    reference = restarted.get(character('We are going north.'))
    assert reference.text == first.text
    assert restarted.options(character('More dialogue.'))[1] is False


def test_narrator_characters_design_changes_and_model_revisions_are_isolated(tmp_path):
    store = VoiceReferences(tmp_path, 'revision-one')
    first = character()
    store.save(first, PCM, RATE)
    assert store.get(plan_speech('A narrator passage.')[0]) is None
    assert store.get(replace(first, identity='someone-else')) is None
    assert store.get(replace(first, style=first.style + ' A softer voice.')) is None
    assert VoiceReferences(tmp_path, 'revision-two').get(first) is None
    # Voice identity is preserved even when long passages are split into chunks.
    segments = plan_speech('“' + 'Stay close to the lantern. ' * 30 + '”', PROFILE)
    assert len(segments) > 1
    assert all(store.key(segment) == store.key(first) for segment in segments)


def test_unusable_clips_do_not_pin_a_voice(tmp_path):
    store = VoiceReferences(tmp_path, 'revision-one')
    for pcm in [b'', PCM[:RATE], PCM * 8, b'\0\0' * RATE * 2]:
        store.save(character(), pcm, RATE)
        assert store.get(character()) is None


def test_corrupt_or_partial_reference_is_regenerated(tmp_path):
    store = VoiceReferences(tmp_path, 'revision-one')
    first = character()
    store.save(first, PCM, RATE)
    path = store.get(first).path
    path.write_bytes(b'broken wav')
    assert store.options(first)[1] is True
    store.save(first, PCM, RATE)
    assert store.get(first) is not None
    metadata = path.with_name('reference.json')
    data = json.loads(metadata.read_text())
    data['sha256'] = 'not-the-audio-hash'
    metadata.write_text(json.dumps(data))
    assert store.get(first) is None
