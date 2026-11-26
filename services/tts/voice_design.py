"""Concise vocal briefs and clean scripts for the pinned Ming checkpoint."""
from dataclasses import asdict, dataclass
import re
from typing import Literal

SpeechLanguage = Literal['zh', 'en', 'de', 'es', 'fr', 'ko', 'ja']
LANGUAGES = ('zh', 'en', 'de', 'es', 'fr', 'ko', 'ja')
VOICE_DESIGN_PREFIX = 'smooth voice,'
FEMALE_GENDERS = {'female', 'woman', 'girl', 'f', 'feminine', 'cis woman', 'trans woman'}
MALE_GENDERS = {'male', 'man', 'boy', 'm', 'masculine', 'cis man', 'trans man'}
NARRATOR_INSTRUCTION = (
    f'{VOICE_DESIGN_PREFIX} A clear adult male storyteller with a natural, balanced timbre, '
    'gentle mid-range pitch and clean studio clarity. '
    'Use an unhurried measured audiobook pace, crisp articulation and naturally varied sentence stress, '
    'with a warm, curious emotional baseline.'
)
# Exact case-sensitive tags from the installed 0.5B cookbook. These are supported
# on Chinese/English reference-based TTS, not combined with Voice Design.
TAGS = ('Breathing', 'Cough', 'Sigh', 'Laughter', 'Uhm', 'Surprise-ah', 'Surprise-oh',
        'Dissatisfaction-hnn', 'Confirmation-en', 'Question-ei', 'Question-ah',
        'Question-en', 'Question-oh', 'Question-huh')
TAG_ALIASES = {tag.lower(): tag for tag in TAGS}
TAG_ALIASES.update({'breath': 'Breathing', 'hesitation': 'Uhm', 'laugh': 'Laughter'})
TAG_PATTERN = re.compile(r'\[(' + '|'.join(map(re.escape, TAGS)) + r')\]')
DIRECTION = re.compile(
    r'^\s*(?:(?:speaks?|says?|reads?|said|spoken)\s+)?'
    r'(?:angrily|sadly|happily|quietly|softly|loudly|excitedly|nervously|sarcastically|'
    r'whispers?|whispering|shouts?|shouting|clears?\s+(?:(?:his|her|their)\s+)?throat|'
    r'pauses?|sighs?|laughs?|chuckles?|breathes?|hesitates?)\s*$', re.I)


@dataclass(frozen=True)
class SpeechPrompt:
    instruction: str
    language_id: SpeechLanguage
    text: str

    def as_fields(self) -> dict:
        return asdict(self)


def positive(text: str, expression: str) -> bool:
    for match in re.finditer(expression, text, re.I):
        if not re.search(r"\b(?:not|never|no|without|isn't|isn’t)\s+(?:\w+\s+){0,2}$",
                         text[max(0, match.start() - 35):match.start()], re.I):
            return True
    return False


def voice_gender(character: dict) -> Literal['female', 'male', '']:
    """The voice's gender presentation: declared gender, then pronouns, then self-description."""
    gender = (character.get('gender') or '').strip().lower()
    pronouns = (character.get('pronouns') or '').strip().lower()
    if gender in FEMALE_GENDERS:
        return 'female'
    if gender in MALE_GENDERS:
        return 'male'
    if gender:
        return ''
    if pronouns:
        she = bool(re.search(r'\b(she|her)\b', pronouns))
        he = bool(re.search(r'\b(he|him)\b', pronouns))
    else:
        profile = (character.get('personality') or '') + ' ' + (character.get('description') or '')
        she = bool(re.search(r'(?:^|[.!?]\s+)She\b', profile, re.I))
        he = bool(re.search(r'(?:^|[.!?]\s+)He\b', profile, re.I))
    return 'female' if she and not he else 'male' if he and not she else ''


def character_instruction(character: dict) -> str:
    personality = (character.get('personality') or '').strip()
    gender = (character.get('gender') or '').strip().lower()
    presentation = voice_gender(character)
    female, male = presentation == 'female', presentation == 'male'
    age = character.get('ageYears')
    age_range = ('child' if age < 13 else 'teenage' if age < 18 else 'young adult' if age < 35
                 else 'adult' if age < 60 else 'older adult') if age is not None else 'adult'
    person = ('girl' if age is not None and age < 18 else 'woman') if female else (
        'boy' if age is not None and age < 18 else 'man') if male else 'speaker'
    if gender in {'nonbinary', 'non-binary', 'agender'}:
        age_range = f'{age_range} {gender}'
    persona = f'a {age_range} {person}'
    if persona.startswith('a adult') or persona.startswith('a older'):
        persona = 'an ' + persona[2:]

    vocal = ' '.join(part for part in re.split(r'(?<=[.!?])\s+|\n+', personality)
                     if re.search(r'\b(?:voice|vocal|timbre|pitch|tone|speaks?|speech|whispers?|cadence|delivery)\b', part, re.I))
    textures = []
    for token, description in [('gravelly', 'gravelly'), ('raspy', 'lightly raspy'), ('breathy', 'soft and breathy'),
                               ('soft', 'soft'), ('dry', 'dry and clear'), ('crisp', 'crisp'),
                               ('resonant', 'resonant'), ('bright', 'bright'), ('warm', 'warm')]:
        if positive(vocal, rf'\b{token}\b'):
            textures.append(description)
    timbre = ' and '.join(textures[:2]) if textures else 'clear, natural'
    pitch = ('a low, resonant register' if positive(vocal, r'\b(?:deep|low[- ]pitched|low pitch)\b')
             else 'a bright upper register' if positive(vocal, r'\b(?:high[- ]pitched|high pitch)\b')
             else 'a natural feminine pitch' if female
             else 'a gentle mid-range pitch')
    pacing = ('unhurried, deliberate pacing with clear pauses' if positive(vocal, r'\b(?:slow(?:ly)?|unhurried|measured|deliberate)\b')
              else 'brisk, energetic pacing' if positive(vocal, r'\b(?:fast|rapid(?:ly)?|brisk)\b')
              else 'natural conversational pacing')
    articulation = ('crisp, direct articulation' if positive(personality, r'\b(?:slang|profanity|blunt|direct)\b')
                    else 'precise, clear articulation' if positive(personality, r'\b(?:precise|meticulous|academic|formal)\b')
                    else 'clear articulation')
    moods = []
    for expression, mood in [
        (r'\b(?:impulsive|playful|cheerful|upbeat|quick to laugh)\b', 'lively'),
        (r'\b(?:cynical|sarcastic|wry|dry humor|dry wit)\b', 'wry'),
        (r'\b(?:stern|authoritative|commanding|disciplined)\b', 'authoritative'),
        (r'\b(?:gentle|reassuring|empathetic|kind|warm)\b', 'reassuring'),
        (r'\b(?:anxious|nervous|timid)\b', 'tentative'),
        (r'\b(?:reserved|guarded|quiet|stoic)\b', 'restrained'),
        (r'\b(?:guilt|grief|melancholic|reflective)\b', 'reflective'),
    ]:
        if positive(personality, expression):
            moods.append(mood)
    baseline = ', '.join(moods[:2]) if moods else 'calm, attentive'
    # Voice Design drifts toward a male voice for a woman unless the brief also
    # opens with an explicit voice label.
    label = ' female voice.' if female else ''
    return (f'{VOICE_DESIGN_PREFIX}{label} {persona.capitalize()} with a {timbre} timbre and {pitch}. '
            f'Use {pacing} and {articulation}, with a {baseline} emotional baseline.')


def sanitize_cues(text: str, language_id: str) -> str:
    # Parentheses containing real prose remain prose; only known stage cues go.
    text = re.sub(r'\(([^()]+)\)', lambda m: '' if DIRECTION.fullmatch(m[1]) else m[0], text)
    return re.sub(r'\[([^\]\n]+)\]', lambda m:
                  f'[{TAG_ALIASES[m[1].strip().lower()]}]'
                  if language_id in {'en', 'zh'} and m[1].strip().lower() in TAG_ALIASES else '', text)


def without_tags(text: str) -> str:
    return re.sub(r'\s+', ' ', TAG_PATTERN.sub('', text)).strip()


def normalize_script(text: str, language_id: str) -> str:
    # Spell ambiguous serials and decimal/version components, not ordinary
    # quantities or locale-dependent dates whose intended reading is unknown.
    digits = {
        'en': 'zero one two three four five six seven eight nine'.split(),
        'de': 'null eins zwei drei vier fünf sechs sieben acht neun'.split(),
        'es': 'cero uno dos tres cuatro cinco seis siete ocho nueve'.split(),
        'fr': 'zéro un deux trois quatre cinq six sept huit neuf'.split(),
        'zh': list('零一二三四五六七八九'),
        'ja': 'ゼロ いち に さん よん ご ろく なな はち きゅう'.split(),
        'ko': '영 일 이 삼 사 오 육 칠 팔 구'.split(),
    }[language_id]
    point = {'en': 'point', 'de': 'Komma', 'es': 'coma', 'fr': 'virgule', 'zh': '点', 'ja': 'てん', 'ko': '점'}[language_id]
    def number(match):
        return f' {point} '.join(' '.join(digits[int(c)] for c in part) for part in match[0].split('.'))
    # A terminal period remains punctuation, not a decimal point.
    text = re.sub(r'\b(?:\d+\.\d+(?:\.\d+)*|0\d+)\b', number, text)
    ampersand = {'en': 'and', 'de': 'und', 'es': 'y', 'fr': 'et', 'zh': '和', 'ja': 'と', 'ko': '그리고'}[language_id]
    text = re.sub(r'\s*&\s*', f' {ampersand} ', text)
    return re.sub(r'[ \t]+', ' ', text).strip()
