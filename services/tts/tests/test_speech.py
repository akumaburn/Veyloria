import base64
import threading
import time
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

import app as service
from speech_plan import NARRATOR_STYLE, plan_speech

CHARACTER = {'id': 'ada', 'name': 'Ada', 'personality': 'Wry, guarded, with a soft dry voice.'}


def test_routes_actions_dialogue_and_narration_in_order():
    plan = plan_speech('*She lifts the lamp.* “Stay close.” The corridor darkens. "Run!"', CHARACTER)
    assert [s.speaker for s in plan] == ['narrator', 'character', 'narrator', 'character']
    assert plan[1].text == 'Stay close.'
    assert 'wry, restrained emotional baseline' in plan[1].style
    assert 'soft' in plan[1].style
    assert plan[0].style == NARRATOR_STYLE
    assert plan[1].seed == plan[3].seed


@pytest.mark.parametrize('character', [None, {}, {'name': 'Ada'}, {'personality': '  '}])
def test_missing_personality_stays_with_narrator(character):
    assert all(s.speaker == 'narrator' for s in plan_speech('The keeper says, "Welcome."', character))


def test_first_person_actions_are_personal_but_unbalanced_quotes_are_not():
    plan = plan_speech('*I remember "stay".* I open the door. "An unfinished thought', CHARACTER)
    assert [s.speaker for s in plan] == ['character', 'narrator']


def test_silent_content_removed_and_personality_changes_seed():
    assert plan_speech('<think>secret</think> ```hidden``` `code`') == []
    assert plan_speech('"Hello."', CHARACTER)[0].seed != plan_speech('"Hello."', {**CHARACTER, 'personality': 'Cheerful'})[0].seed


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("MING_VOICE_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(service, 'load_model', lambda: SimpleNamespace(sample_rate=24000, local_path='/snapshots/test-revision'))
    monkeypatch.setattr(service, 'synthesize', lambda model, segment: (base64.b64encode(b'\0\0' * 24).decode(), .001))
    with TestClient(service.app) as client:
        yield client


def test_websocket_protocol_and_empty_message_completion(client):
    assert client.get('/health').json()['mode'] == 'VOICE_DESIGN'
    assert client.get('/api/voices').json()['data'][0]['name'] == 'narrator'
    with client.websocket_connect('/ws/tts') as ws:
        ws.send_json({'action': 'synthesize', 'text': '*She nods.* "Yes."', 'character': CHARACTER})
        assert ws.receive_json() == {'type': 'START', 'sample_rate': 24000, 'total_chunks': 2}
        assert ws.receive_json()['speaker'] == 'narrator'
        assert ws.receive_json()['speaker'] == 'character'
        assert ws.receive_json()['type'] == 'COMPLETE'
    with client.websocket_connect('/ws/tts') as ws:
        ws.send_json({'text': '<think>private</think>'})
        assert ws.receive_json()['total_chunks'] == 0
        assert ws.receive_json()['type'] == 'COMPLETE'


def test_bad_request_and_failed_inference_finish_with_error(client, monkeypatch):
    with client.websocket_connect('/ws/tts') as ws:
        ws.send_json({'text': 'x' * 32001})
        assert ws.receive_json()['type'] == 'ERROR'
    def fail(*args):
        raise RuntimeError('GPU failed')
    monkeypatch.setattr(service, 'synthesize', fail)
    with client.websocket_connect('/ws/tts') as ws:
        ws.send_json({'text': 'hello'})
        assert ws.receive_json()['type'] == 'START'
        assert ws.receive_json()['type'] == 'ERROR'


def test_model_download_uses_cached_hub_and_configured_revision(monkeypatch):
    import sys
    calls = []
    monkeypatch.setitem(sys.modules, 'huggingface_hub', SimpleNamespace(snapshot_download=lambda *a, **kw: calls.append(kw) or '/cached/model'))
    monkeypatch.setitem(sys.modules, 'ming_tts', SimpleNamespace(MingTTS=lambda *a, **kw: (a, kw)))
    monkeypatch.setenv('MING_MODEL_REVISION', 'pinned-revision')
    args, kwargs = service.load_model()
    assert args == ('/cached/model',)
    assert calls[0]['revision'] == 'pinned-revision'
    assert kwargs['text_normalization'] is False


def test_inference_is_serialized_across_clients(client, monkeypatch):
    active = 0
    peak = 0
    lock = threading.Lock()
    def synthesize(*args):
        nonlocal active, peak
        with lock:
            active += 1
            peak = max(peak, active)
        time.sleep(.03)
        with lock:
            active -= 1
        return '', 0
    monkeypatch.setattr(service, 'synthesize', synthesize)
    def request():
        with client.websocket_connect('/ws/tts') as ws:
            ws.send_json({'text': 'hello'})
            while ws.receive_json()['type'] != 'COMPLETE':
                pass
    workers = [threading.Thread(target=request) for _ in range(2)]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    assert peak == 1


def test_nullable_personality_and_long_passages(client):
    with client.websocket_connect('/ws/tts') as ws:
        ws.send_json({'text': '"Welcome."', 'character': {'personality': None}})
        assert ws.receive_json()['type'] == 'START'
        assert ws.receive_json()['speaker'] == 'narrator'
        assert ws.receive_json()['type'] == 'COMPLETE'
    text = 'A long road stretches ahead. ' * 100
    segments = plan_speech(text, CHARACTER)
    assert all(len(s.text) <= 200 for s in segments)
    assert ' '.join(s.text for s in segments) == text.strip()


def test_declared_female_identity_is_explicit_and_survives_personality_delivery():
    profile = {'id': 'elara', 'name': 'Elara Thorne', 'gender': 'female', 'pronouns': 'she/her', 'ageYears': 27,
               'personality': 'Impulsive, fiercely loyal, and cynical about institutional power. She speaks with a mix of academic slang and street profanity.'}
    plan = plan_speech('*She nods.* “We move north.”', profile)
    assert plan[0].style == NARRATOR_STYLE
    assert 'adult male' in plan[0].style
    assert plan[0].identity == 'narrator'
    assert 'young adult woman' in plan[1].style
    assert 'lively, wry emotional baseline' in plan[1].style
    assert 'young adult' in plan[1].style
    assert plan[0].seed != plan[1].seed


def test_declared_gender_precedes_incidental_pronouns():
    profile = {'personality': 'He often calls her for advice.', 'gender': 'female'}
    assert 'adult woman' in plan_speech('"Hello."', profile)[0].style
    profile['gender'] = 'nonbinary'
    style = plan_speech('"Hello."', profile)[0].style
    assert 'adult nonbinary speaker' in style
    assert 'adult man' not in style and 'adult woman' not in style


def test_legacy_self_pronouns_and_structured_pronouns_support_existing_characters():
    for profile in [{'personality': 'Impulsive and loyal. She speaks with academic slang.'},
                    {'personality': 'Quiet and precise.', 'pronouns': 'she/her'}]:
        assert 'adult woman' in plan_speech('"Hello."', profile)[0].style
    # Names and mixed references do not determine vocal gender.
    assert 'adult woman' not in plan_speech('"Hello."', {'name': 'Elara', 'personality': 'He and she argue.'})[0].style


def test_character_profile_is_accepted_by_websocket_validation(client, monkeypatch):
    styles = []
    monkeypatch.setattr(service, 'synthesize', lambda model, segment: (styles.append(segment.style) or '', 0))
    with client.websocket_connect('/ws/tts') as ws:
        ws.send_json({'text': '"Hello."', 'character': {'personality': 'Quiet.', 'gender': 'female', 'pronouns': 'she/her', 'ageYears': 27}})
        assert ws.receive_json()['type'] == 'START'
        assert ws.receive_json()['type'] == 'CHUNK'
        assert ws.receive_json()['type'] == 'COMPLETE'
    assert 'adult woman' in styles[0]


def test_narration_keeps_sentence_boundaries_for_pacing():
    plan = plan_speech('The corridor is quiet. A door opens.\nThe lantern flickers.')
    assert [s.text for s in plan] == ['The corridor is quiet.', 'A door opens.', 'The lantern flickers.']
    assert all(s.speaker == 'narrator' for s in plan)
    assert 'measured audiobook pace' in plan[0].style


def test_narration_pacing_does_not_pause_inside_abbreviated_names():
    plan = plan_speech('Dr. Mira waits. The U.S. envoy arrives.')
    assert [s.text for s in plan] == ['Dr. Mira waits.', 'The U.S. envoy arrives.']



def test_speech_plan_exposes_three_separate_copy_ready_fields(client):
    result = client.post('/api/speech-plan', json={'text': '“(angrily) Bonjour.”', 'character': CHARACTER, 'language_id': 'fr'})
    assert result.status_code == 200
    fields = result.json()['segments'][0]
    assert set(fields) == {'instruction', 'language_id', 'text'}
    assert fields['text'] == 'Bonjour.'
    assert fields['language_id'] == 'fr'
    assert fields['instruction'].startswith('smooth voice, ')
    assert client.post('/api/speech-plan', json={'text': 'Hello.', 'language_id': 'xx'}).status_code == 422


def test_websocket_buffers_long_sentence_continuations_into_one_chunk(client):
    text = ', '.join(['The traveler follows the winding road through the quiet valley'] * 16) + '.'
    internal = plan_speech(text)
    assert len(internal) > 1
    with client.websocket_connect('/ws/tts') as ws:
        ws.send_json({'text': text})
        assert ws.receive_json()['total_chunks'] == 1
        chunk = ws.receive_json()
        assert chunk['type'] == 'CHUNK'
        assert chunk['chunk_index'] == 0
        assert chunk['chunk_text'] == text
        assert len(base64.b64decode(chunk['audio_base64'])) == 48 * len(internal)
        assert ws.receive_json()['type'] == 'COMPLETE'
