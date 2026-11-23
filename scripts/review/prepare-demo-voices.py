"""Prepare fictional demo voices with the actual adapter before recording takes.

Only the run-specific reference cache is used. No saved user voices are read.
"""
import base64
import os
import shutil
import wave
import asyncio
import json
from pathlib import Path
import sys
import urllib.request
import websockets

output=Path(sys.argv[1]);run=json.loads((output/'run.json').read_text());fixture=json.loads((output/'fixture.json').read_text())
query={'query':'query($id:String!){character(id:$id){id name gender pronouns ageYears personality description}}','variables':{'id':fixture['id']['mira']}}
req=urllib.request.Request(run['apiURL'],data=json.dumps(query).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+fixture['owner']['accessToken']})
with urllib.request.urlopen(req) as response:
    body=json.load(response)
if body.get('errors'):raise RuntimeError(body['errors'])
character=body['data']['character']
presenter={'id':'demo-presenter-romantic-v2','name':'Demo Presenter','gender':'female','ageYears':35,'personality':'Gentle, warm and reflective. She speaks with a warm, clear voice and unhurried, deliberate pacing.'}
url=run['baseURL'].replace('http:','ws:')+'/tts/ws/tts'

async def synth(text,profile=None,filename=None):
    chunks=[];pcm=[];rate=24000
    async with websockets.connect(url,max_size=32*1024*1024) as socket:
        await socket.send(json.dumps({'action':'synthesize','text':text,'character':profile,'language_id':'en'}))
        async for frame in socket:
            item=json.loads(frame)
            if item['type']=='ERROR':raise RuntimeError(item)
            if item['type']=='START':rate=item['sample_rate']
            if item['type']=='CHUNK':
                chunks.append({key:item[key] for key in ['speaker','duration','chunk_text']});pcm.append(base64.b64decode(item['audio_base64']))
            if item['type']=='COMPLETE':break
    if filename:
        with wave.open(str(filename),'wb') as audio:
            audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(rate);audio.writeframes(b''.join(pcm))
    return chunks

async def main():
    report=[{'presenter_profile':presenter,'purpose':'Promotional commentary only; product narrator and Mira remain independent.'}]
    for name,profile,texts in [
        ('Presenter',presenter,['"Every world begins with a little longing. A place to belong, and someone to walk beside."','"Beyond the harbor, the stars are waiting. We have a little farther to go."']),
        ('narrator',None,['The party waited beside the lanterns. The market was quiet.','Beyond the market, the Lantern Inn offered a warm welcome.']),
        ('Mira',character,['"I tuck a stray strand behind my ear. C I D sent us to the old observatory."','"The lanterns are bright tonight. We should ask at the inn."','"I remember the path. We should leave before the tide turns."'])]:
        ready=False
        for text in texts:
            chunks=await synth(text,profile);report.append({'speaker':name,'script':text,'chunks':chunks})
            if any(1<=chunk['duration']<=15 and chunk['speaker']==('character' if profile else 'narrator') for chunk in chunks):ready=True;break
        if not ready:raise RuntimeError('No suitable acoustic anchor for '+name)
    lines={
        'premise':'Every world begins with a longing. A place to belong, and a story still waiting to be told.',
        'templates':'Begin with a familiar tale, or find a new beginning among the story templates. Adventure, mystery, romance, and more.',
        'character-prompt':'Imagine someone worth meeting. Their voice, their history, the small things that make them feel alive.',
        'character-result':'From a few words, a character takes shape. Review their identity and personality, then make them your own.',
        'roster-prompt':'Some stories begin with one voice. Others begin with lives already entwined, shaped by the same place and its history.',
        'roster-result':'A stargazer, a ranger, an envoy. Different hopes and loyalties, with feelings and connections that bind the cast together.',
        'roster-saved':'Save the whole ensemble as a roster, ready to bring into the stories you will tell.',
        'relationships':'Every connection has a direction. See who cares, who trusts, and who still carries a little resentment.',
        'relationship-direction':'What one person feels may not be returned in quite the same way. Each side keeps its own measure.',
        'alignment':'Alignment gives those choices a compass, from law to chaos, and from kindness to darker motives.',
        'map-design':'Then give them a world. Choose its scale, its climate, its water, and the seed from which it grows.',
        'map-generated':'Land and water become terrain. Settlements find their places, and roads draw the distant corners together.',
        'opening':'Now the story can breathe. A narrator sets the scene, and your companions answer in voices of their own.',
        'health-before':'Along the way, care becomes something tangible. Mira is hurt, and there is a healing draught to spare.',
        'health-after':'Her health returns. One draught is gone. Even a small kindness leaves its trace in the world.',
        'local-before':'The town remembers where we have been. Streets, rooms, and familiar doors remain connected on the local map.',
        'arrival':'Choose the inn, and the journey becomes an arrival. Beyond the doorway, someone is there to welcome us.',
        'empires-2d':'Beyond the town lie the empires. Their territories and cultures give the landscape a history of its own.',
        'empires-3d':'Those same borders stretch across the three-dimensional world, tracing the lands that different peoples call home.',
        'route':'Past the harbor gate, the old observatory waits. We choose the road that will carry us there.',
        'journey':'The party travels, the narrator follows, and the world keeps its place beneath every step.',
        'vote':'When danger finds us, the choice belongs to the party. Two voices gather around one plan.',
        'combat-result':'The chosen action becomes a narrated outcome. Health changes, a new round opens, and the next decision is ours.',
        'dice':'Sometimes, we leave a little room for chance. Roll the dice, name the purpose, and keep the result.',
        'bookmarks':'Some moments deserve to be kept close. A bookmark marks the place we may wish to return to.',
        'branching':'In narrator mode, another road can open from a saved moment. A branch lets a different possibility unfold.',
        'world-state':'Place, time, weather, and mood remain part of the same recorded world. The story carries its circumstances with it.',
        'knowledge':'For the storyteller, memories remain within reach. Narrator tools reveal the knowledge behind the unfolding tale.',
        'agents':'Behind the scenes, focused agents have their own work. Choose their models and inspect the process in narrator mode.',
        'illustrations':'Illustrations can join the story through an optional ComfyUI connection. This capture shows the integration, without a connected image provider.',
        'publish':'When the time comes, give the journey a title and a description. Share what your party has made together.',
        'community':'Share a title, a synopsis, and a place in the community, where others can discover and rate the adventure.',
        'party':'And there is always room for someone beside you. Create a party, and share the invitation to begin together.',
        'achievements':'Milestones become a record of experience, of stories completed, and of the paths already walked.',
        'portability':'Keep the things you have made. Export conversations, characters, and settings, with an import path when you need them again.',
        'settings':'Make the experience feel like home. Choose your models, your voices, your appearance, and the details that suit you.',
        'ending':'A world to wander. People to remember. And a story that becomes yours. Veyloria.',
    }
    narration=output/'demo-narration';narration.mkdir(exist_ok=True)
    reuse_path=os.getenv('REVIEW_DEMO_NARRATION_FROM')
    previous=Path(reuse_path) if reuse_path else None
    previous_report=json.loads((previous/'demo-voice-preparation.json').read_text()) if previous and (previous/'demo-voice-preparation.json').exists() else []
    previous_lines={item['commentary']:item for item in previous_report if 'commentary' in item}
    reuse=bool(previous and previous_report and previous_report[0].get('presenter_profile')==presenter and all(previous_lines.get(name,{}).get('script')==text and (previous/'demo-narration'/(name+'.wav')).is_file() for name,text in lines.items()))
    for name,text in lines.items():
        if reuse:
            shutil.copy2(previous/'demo-narration'/(name+'.wav'),narration/(name+'.wav'))
            report.append({**previous_lines[name],'reused_from':str(previous)})
        else:
            result=await synth(json.dumps(text),profile=presenter,filename=narration/(name+'.wav'));report.append({'commentary':name,'script':text,'chunks':result})
    (output/'demo-voice-preparation.json').write_text(json.dumps(report,indent=2))
    print('Prepared promotional presenter, real narrator and Mira voice anchors in the isolated demo cache.')
asyncio.run(main())
