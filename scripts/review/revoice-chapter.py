"""Revise one promotional chapter using its isolated acoustic reference.

Usage: PYTHONPATH=services/tts:.runtime/tts/ming <speech-python> \
    scripts/review/revoice-chapter.py <run-directory> <chapter> <script-file>
"""
import base64
import json
import shutil
import sys
import wave
from pathlib import Path

from app import load_model, synthesize_group
from speech_plan import plan_speech, playback_groups
from voice_references import VoiceReferences

output = Path(sys.argv[1]).resolve()
chapter = sys.argv[2]
script = Path(sys.argv[3]).read_text().strip()
report_file = output / 'demo-voice-preparation.json'
report = json.loads(report_file.read_text())
profile = report[0]['presenter_profile']
entry = next(item for item in report if item.get('commentary') == chapter)
filename = output / 'demo-narration' / (chapter + '.wav')
backup = filename.with_suffix('.before-revision.wav')
if not backup.exists():
    shutil.copy2(filename, backup)
model = load_model()
model.voice_references = VoiceReferences(output / 'demo-voice-references', Path(model.local_path).name)
chunks = []
pcm = []
for group in playback_groups(plan_speech(json.dumps(script), profile, 'en')):
    encoded, duration = synthesize_group(model, group)
    pcm.append(base64.b64decode(encoded))
    chunks.append({'speaker': group[0].speaker, 'duration': duration,
                   'chunk_text': ' '.join(segment.text for segment in group)})
with wave.open(str(filename), 'wb') as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(model.sample_rate)
    audio.writeframes(b''.join(pcm))
entry.clear()
entry.update(commentary=chapter, script=script, chunks=chunks,
             revision='Re-synthesized with the same isolated promotional voice reference.')
report_file.write_text(json.dumps(report, indent=2))
print(filename)
