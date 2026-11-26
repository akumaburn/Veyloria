from dataclasses import replace
import struct
import pytest

from speech_plan import plan_speech
from voice_design import TAGS, SpeechPrompt, character_instruction, voice_gender
from voice_references import VoiceReferences

ELARA = {'id': 'elara', 'gender': 'Woman', 'ageYears': 24, 'personality':
         "Impulsive, fiercely loyal, and cynical about institutional power. She speaks with a mix of academic slang and street profanity. She is quick to laugh but carries a heavy guilt over those she couldn't save."}


def test_concise_character_brief_covers_four_dimensions_and_does_not_dump_biography():
    instruction = character_instruction(ELARA)
    assert len(instruction.split('. ')) == 2
    assert 'young adult woman' in instruction
    assert 'timbre' in instruction and 'pitch' in instruction
    assert 'pacing' in instruction and 'articulation' in instruction
    assert 'lively, wry emotional baseline' in instruction
    assert ELARA['personality'] not in instruction
    assert 'institutional power' not in instruction
    assert len(instruction.split()) < 60


def test_explicit_vocal_cues_are_prioritized_over_generic_defaults():
    instruction = character_instruction({'gender': 'Man', 'ageYears': 70, 'personality':
                                          'A stern gatekeeper. He speaks slowly with a deep, gravelly voice.'})
    assert 'older adult man' in instruction
    assert 'gravelly' in instruction and 'low, resonant register' in instruction
    assert 'unhurried, deliberate pacing' in instruction
    assert 'authoritative emotional baseline' in instruction


def test_copy_ready_fields_never_mix_instruction_into_the_script():
    segment = plan_speech('“(angrily) I check my CID before we leave.”', ELARA)[0]
    fields = segment.prompt().as_fields()
    assert set(fields) == {'instruction', 'language_id', 'text'}
    assert fields['language_id'] == 'en'
    assert fields['text'] == 'I check my C I D before we leave.'
    assert fields['instruction'].startswith('smooth voice, ')
    assert 'angrily' not in fields['text']


def test_known_stage_cues_are_removed_but_parenthetical_dialogue_and_punctuation_are_kept():
    text = '“(clears throat) Wait—(if you can) take a breath... (angrily) Now!”'
    segment = plan_speech(text, ELARA)[0]
    assert segment.text == 'Wait—(if you can) take a breath... Now!'


def test_paralinguistic_tags_are_canonical_and_exclusive_to_supported_cloning_mode(tmp_path):
    store = VoiceReferences(tmp_path, 'revision')
    segment = plan_speech('“[sigh] I checked the door. [laughter] [breath] [hesitation] [angry]”', ELARA)[0]
    assert '[Sigh]' in segment.text and '[Laughter]' in segment.text
    assert '[Breathing]' in segment.text and '[Uhm]' in segment.text
    assert '[angry]' not in segment.text
    options, designed = store.options(segment)
    assert designed and options['task'] == 'VOICE_DESIGN'
    assert '[' not in options['text']
    assert set(options['controls']) == {'style'}
    assert 'instruction' not in options
    reference_segment = replace(segment, text=options['text'])
    store.save(reference_segment, struct.pack('<h', 1000) * 48000, 24000)
    options, designed = store.options(segment)
    assert not designed and options['task'] == 'TTS'
    assert '[Sigh]' in options['text']
    assert options['prompt_text'] == reference_segment.text
    assert 'controls' not in options and 'instruction' not in options


@pytest.mark.parametrize('language', ['zh', 'en', 'de', 'es', 'fr', 'ko', 'ja'])
def test_language_is_explicit_in_design_and_reference_continuations(tmp_path, language):
    store = VoiceReferences(tmp_path, 'revision')
    segment = plan_speech('“Welcome.”', ELARA, language)[0]
    options, designed = store.options(segment)
    assert designed and options['language_id'] == language
    if language not in {'en', 'zh'}:
        assert '[' not in plan_speech('“[sigh] Welcome.”', ELARA, language)[0].text
    store.save(segment, struct.pack('<h', 1000) * 48000, 24000)
    options, designed = store.options(segment)
    assert not designed
    assert options['language_id'] == language
    assert options['task'] == ('TTS' if language in {'en', 'zh'} else 'MULTILINGUAL')
    assert store.get(replace(segment, language_id='fr' if language != 'fr' else 'de')) is None


def test_unknown_languages_are_rejected_before_generation():
    with pytest.raises(ValueError, match='Unsupported speech language'):
        plan_speech('Hello.', None, 'xx')


def test_decimal_and_serial_readings_are_in_the_requested_language():
    assert plan_speech('Code 007, version 3.14 & rising.')[0].text == 'Code zero zero seven, version three point one four and rising.'
    assert plan_speech('Code 007 et 3.14.', language_id='fr')[0].text == 'Code zéro zéro sept et trois virgule un quatre.'


def test_cjk_sentence_punctuation_is_preserved():
    assert [s.text for s in plan_speech('灯亮了。门开了。', language_id='zh')] == ['灯亮了。', '门开了。']


def test_female_brief_states_a_feminine_register_and_male_brief_is_unchanged():
    assert 'a natural feminine pitch' in character_instruction({'gender': 'Woman', 'personality': 'Calm.'})
    assert 'a gentle mid-range pitch' in character_instruction({'gender': 'Man', 'personality': 'Calm.'})
    assert 'a gentle mid-range pitch' in character_instruction({'personality': 'Calm.'})
    deep = character_instruction({'gender': 'Woman', 'personality': 'She speaks in a deep voice.'})
    assert 'low, resonant register' in deep and 'feminine pitch' not in deep


@pytest.mark.parametrize('profile, expected', [
    ({'gender': 'Woman', 'pronouns': 'he/him'}, 'female'),
    ({'gender': 'nonbinary', 'pronouns': 'she/her'}, ''),
    ({'pronouns': 'she/her'}, 'female'),
    ({'pronouns': 'he/they'}, 'male'),
    ({'personality': 'Weary. She speaks softly.'}, 'female'),
    ({'personality': 'She leads. He follows.'}, ''),
    ({'name': 'Elara'}, ''),
])
def test_voice_gender_prefers_declared_gender_then_pronouns_then_self_description(profile, expected):
    assert voice_gender(profile) == expected


def test_segments_carry_the_presentation_their_designed_voice_must_match():
    segments = plan_speech('The rain falls. “Hold still.”', {'id': 'elara', 'gender': 'Woman', 'personality': 'Calm.'})
    assert [(s.speaker, s.voice_gender) for s in segments] == [('narrator', 'male'), ('character', 'female')]
    unknown = plan_speech('“Hold still.”', {'id': 'riven', 'gender': 'nonbinary', 'personality': 'Calm.'})[0]
    assert unknown.voice_gender == ''
