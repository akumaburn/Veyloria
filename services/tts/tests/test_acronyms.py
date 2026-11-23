from speech_plan import plan_speech, spell_acronyms
from voice_references import VoiceReferences
import struct


PROFILE = {'id': 'elara', 'personality': 'She checks her CID before leaving.', 'gender': 'Woman'}


def test_expands_acronyms_in_narration_and_dialogue_without_changing_voice_design():
    plan = plan_speech('The CID glows. “Check your CID and the NPC roster.”', PROFILE)
    assert [(segment.speaker, segment.text) for segment in plan] == [
        ('narrator', 'The C I D glows.'),
        ('character', 'Check your C I D and the N P C roster.'),
    ]
    assert 'adult woman' in plan[1].style
    assert 'CID' not in plan[1].style


def test_preserves_punctuation_possessives_and_already_spelled_letters():
    assert spell_acronyms("CID, NPC! AI? CID's UTF-8: C I D.") == "C I D, N P C! A I? C I D's U T F-8: C I D."


def test_leaves_normal_words_mixed_case_and_embedded_identifiers_unchanged():
    text = 'I met Elara by the Cid gate. iPhone cid CID42 myCID CID_name.'
    assert spell_acronyms(text) == text


def test_expansion_happens_before_chunk_limits_and_is_idempotent():
    text = 'Check the CID before leaving. ' * 30
    segments = plan_speech(text)
    assert all(len(segment.text) <= 200 for segment in segments)
    combined = ' '.join(segment.text for segment in segments)
    assert combined == spell_acronyms(text).strip()
    assert spell_acronyms(combined) == combined


def test_new_references_save_expanded_transcript_and_existing_references_stay_exact(tmp_path):
    store = VoiceReferences(tmp_path, 'model-revision')
    current = plan_speech('“Please check your CID before we leave.”', PROFILE)[0]
    legacy = type(current)('Please check your CID before we leave.', current.speaker,
                           current.style, current.seed, current.identity)
    pcm = struct.pack('<h', 1000) * 48000
    store.save(legacy, pcm, 24000)
    options, designed = store.options(current)
    assert not designed
    assert options['text'] == 'Please check your C I D before we leave.'
    # Reference text describes its existing audio; rewriting it would misalign cloning.
    assert options['prompt_text'] == legacy.text
    new_store = VoiceReferences(tmp_path / 'new', 'model-revision')
    new_store.save(current, pcm, 24000)
    assert new_store.get(current).text == current.text
