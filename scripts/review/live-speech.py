"""Optional real Ming smoke/voiceover, using only fictional review scripts and fresh references."""
import base64
import json
import os
from pathlib import Path
import sys
import time
import wave

from app import load_model, synthesize_group
from speech_plan import plan_speech, playback_groups
from voice_references import VoiceReferences

output = Path(sys.argv[1]).resolve()
started = time.monotonic()
model = load_model()
model.voice_references = VoiceReferences(output / 'live-voice-references', Path(model.local_path).name)
report = {'model_revision': Path(model.local_path).name, 'checks': [], 'quality_review': 'Audio artifacts require listening; automated checks cover synthesis, PCM and references.'}


def generate(name, text, character=None):
    segments = plan_speech(text, character, 'en')
    chunks = [base64.b64decode(synthesize_group(model, group)[0]) for group in playback_groups(segments)]
    pcm = b''.join(chunks)
    if not pcm or not any(pcm):
        raise RuntimeError('Real Ming returned silent or empty PCM')
    with wave.open(str(output / (name + '.wav')), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(model.sample_rate)
        audio.writeframes(pcm)
    report['checks'].append({'file': name+'.wav', 'seconds':len(pcm)/(2*model.sample_rate), 'prompts':[s.prompt().as_fields() for s in segments]})


generate('narrator-smoke', 'Evening settled over Lantern Quay. Beyond the market, the Lantern Inn offered a warm welcome.')
mira = {'id':'review-mira','name':'Mira Solstice','gender':'female','pronouns':'she/her','ageYears':26,'personality':'Impulsive, fiercely loyal, and cynical about institutional power. She speaks with a mix of academic slang and street profanity.'}
generate('mira-smoke', '*I tuck a stray strand behind my ear.* "C I D sent us to the old observatory."', mira)
generate('mira-followup', '"The innkeeper may know a safer path. We should ask before the tide turns."', mira)
chapters = [
    'Step into Veyloria, a shared fantasy world shaped by your choices.',
    'Meet companions with their own personalities.',
    'Explore local streets and follow each passage.',
    'Discover a wider world of procedural landscapes.',
    'Manage equipment, use items, and share supplies.',
    'Face narrative encounters together, and vote on your next move.',
    'Hear the narrator and your characters in distinct voices.',
    'Choose a destination. Arrive in the story, and discover who is waiting.',
]
for index, words in enumerate(chapters):
    generate(f'voiceover-{index}', words)
report['reference_files'] = [str(p.relative_to(output)) for p in (output/'live-voice-references').rglob('*.wav')]
if len(report['reference_files']) < 2:
    raise RuntimeError('Narrator and character acoustic references were not saved')
report['elapsed_seconds'] = time.monotonic()-started
(output/'live-speech.json').write_text(json.dumps(report,indent=2))
print(json.dumps({key:value for key,value in report.items() if key!='checks'},indent=2))
