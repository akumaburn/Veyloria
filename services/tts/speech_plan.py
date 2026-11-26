"""Deterministic speaker routing; no LLM guesses about who owns a quotation."""
from dataclasses import dataclass, replace
import hashlib
import re

from voice_design import (
    SpeechPrompt, SpeechLanguage, LANGUAGES, VOICE_DESIGN_PREFIX,
    NARRATOR_INSTRUCTION, character_instruction, voice_gender, sanitize_cues, normalize_script, without_tags,
)

NARRATOR_STYLE = NARRATOR_INSTRUCTION
NARRATOR_GENDER = 'male'


@dataclass(frozen=True)
class Segment:
    text: str
    speaker: str
    style: str
    seed: int
    identity: str = ''
    language_id: SpeechLanguage = 'en'
    pause_after: bool = True
    # The presentation a newly designed voice must match before it is pinned.
    voice_gender: str = ''

    def prompt(self) -> SpeechPrompt:
        return SpeechPrompt(self.style, self.language_id, self.text)


def clean(text: str) -> str:
    text = re.sub(r'<think(?:ing)?>.*?(?:</think(?:ing)?>|$)', '', text, flags=re.S | re.I)
    text = re.sub(r'```.*?(?:```|$)|`[^`]*`', '', text, flags=re.S)
    text = re.sub(r'!\[[^\]]*\]\([^)]*\)', '', text)
    text = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', text)
    return text.strip()


def repair_action_quotes(text: str) -> str:
    """Drop an orphan opening quote around a standalone italic action paragraph.

    Generated replies sometimes start with `"*I act.*` before a blank line and
    properly quoted dialogue. The orphan quote must not hide the whole action's
    viewpoint. A closing outer quote, or prose outside the emphasis, is left alone.
    """
    return re.sub(r'(?m)^([ \t]*)["“](\*(?!\s)[^\n]*\*)[ \t]*(?=\n[ \t]*\n|$)', r'\1\2', text)


def spell_acronyms(text: str) -> str:
    """Speak standalone uppercase letter tokens as initialisms (CID -> C I D)."""
    return re.sub(r'\b[A-Z]{2,}\b', lambda match: ' '.join(match.group()), text)


def character_style(character: dict) -> str:
    return character_instruction(character)


FIRST_PERSON = re.compile(r"\b(?:I|me|my|mine|myself|we|us|our|ours|ourselves)\b", re.I)
ABBREVIATION_END = re.compile(
    r'\b(?:Mr|Mrs|Ms|Mx|Dr|Prof|Sr|Jr|St|Capt|Lt|Gen|Sgt|vs|e\.g|i\.e)\.$|(?:\b[A-Za-z]\.){2,}$', re.I)
NESTED_QUOTES = re.compile(r'"[^"]*(?:"|$)|“[^”]*(?:”|$)', re.S)


def sentence_end(text: str) -> bool:
    return bool(re.search(r'[.!?。！？]["”’\)]*$', text.rstrip())) and not bool(ABBREVIATION_END.search(text.rstrip()))


def narrative_spans(text: str):
    """Route each sentence by its own viewpoint, including italic actions."""
    # Keep offsets while masking reported quotations, so someone else's quoted
    # "I" cannot turn third-person narration into the author's inner voice.
    perspective = NESTED_QUOTES.sub(lambda match: ' ' * len(match.group()), text)
    start = 0
    for boundary in re.finditer(r'(?<=[.!?])\s+|(?<=[。！？])\s*|\n\s*\n', text):
        if ABBREVIATION_END.search(text[:boundary.start()]) or text[:boundary.start()].endswith(('...', '…')):
            continue
        yield text[start:boundary.start()], first_person(perspective[start:boundary.start()])
        start = boundary.end()
    yield text[start:], first_person(perspective[start:])


def first_person(text: str) -> bool:
    for match in FIRST_PERSON.finditer(text):
        token = match.group()
        if token == 'US':  # country abbreviation, not the object pronoun
            continue
        if token.lower() == 'mine' and re.search(
                r'\b(?:the|a|an|this|that|coal|silver|gold|iron)\s+$', text[:match.start()], re.I):
            continue
        return True
    return False


def plan_speech(text: str, character: dict | None = None, language_id: SpeechLanguage = 'en') -> list[Segment]:
    if language_id not in LANGUAGES:
        raise ValueError('Unsupported speech language')
    text = repair_action_quotes(sanitize_cues(clean(text), language_id).replace('**', ''))
    personality = ((character or {}).get('personality') or '').strip()
    style = character_style(character) if personality else NARRATOR_STYLE
    identity = (character or {}).get('id') or (character or {}).get('name', '')
    seed = int.from_bytes(hashlib.sha256(f'{identity}:{style}'.encode()).digest()[:4], 'big')
    presentation = voice_gender(character) if personality else NARRATOR_GENDER
    narrator_seed = 42
    segments = []

    def add(value: str, character_voice: bool = False):
        value = normalize_script(spell_acronyms(re.sub(r'[*_#~]+', '', value)), language_id)
        value = re.sub(r'\s+', ' ', value).strip()
        if not value or not re.search(r'\w', without_tags(value)):
            return
        speaker = 'character' if character_voice and personality else 'narrator'
        segment = Segment(value, speaker, style if speaker == 'character' else NARRATOR_STYLE,
                          seed if speaker == 'character' else narrator_seed,
                          identity if speaker == 'character' else 'narrator', language_id,
                          voice_gender=presentation if speaker == 'character' else NARRATOR_GENDER)
        if segments and segments[-1].speaker == speaker and (speaker == 'character' or not sentence_end(segments[-1].text)):
            previous = segments.pop()
            segment = replace(segment, text=previous.text + ' ' + value)
        segments.append(segment)

    def add_narrative(value: str):
        for sentence, personal in narrative_spans(value):
            add(sentence, personal)

    # Inline emphasis is formatting, not an audible boundary. Keep actions in
    # their surrounding sentence, masking their reported quotes for viewpoint.
    pattern = re.compile(r'\*(?!\s).*?\*|"([^"\n]+)"|“([^”]+)”', re.S)
    end = 0
    narrative = ''
    for match in pattern.finditer(text):
        narrative += text[end:match.start()]
        quoted = match.group(1) or match.group(2)
        if quoted is not None:
            add_narrative(narrative)
            narrative = ''
            add(quoted, True)
        else:
            narrative += match.group().strip('*')
        end = match.end()
    add_narrative(narrative + text[end:])
    # 200 characters is a soft target: never cut a normal sentence to fit it.
    # Only exceptional sentences need clause/word continuations, which playback
    # buffers together so GPU latency cannot introduce a mid-sentence silence.
    bounded = []
    for segment in segments:
        pending = ''
        sentences = [sentence.strip() for sentence, _ in narrative_spans(segment.text) if sentence.strip()]
        for sentence in sentences:
            if pending and len(pending) + len(sentence) + 1 > 200:
                bounded.append(replace(segment, text=pending, pause_after=sentence_end(pending)))
                pending = ''
            while len(sentence) > 600:
                if pending:
                    bounded.append(replace(segment, text=pending, pause_after=sentence_end(pending)))
                    pending = ''
                clauses = list(re.finditer(r'[,;:—，；：]\s*', sentence[:600]))
                boundary = clauses[-1].end() if clauses and clauses[-1].end() >= 160 else sentence.rfind(' ', 0, 600)
                # CJK can continue between characters; never cut an English token.
                if boundary < 1:
                    boundary = 600 if re.search(r'[\u3000-\u9fff]', sentence[:600]) else len(sentence)
                bounded.append(replace(segment, text=sentence[:boundary].strip(), pause_after=False))
                sentence = sentence[boundary:].strip()
            pending = (pending + ' ' + sentence).strip()
        if pending:
            bounded.append(replace(segment, text=pending, pause_after=sentence_end(pending)))
    if bounded:
        bounded[-1] = replace(bounded[-1], pause_after=True)
    return bounded


def playback_groups(segments: list[Segment]) -> list[list[Segment]]:
    groups = []
    for segment in segments:
        previous = groups[-1][-1] if groups else None
        if previous and not previous.pause_after and (previous.speaker, previous.identity, previous.style, previous.language_id) == (segment.speaker, segment.identity, segment.style, segment.language_id):
            groups[-1].append(segment)
        else:
            groups.append([segment])
    return groups
