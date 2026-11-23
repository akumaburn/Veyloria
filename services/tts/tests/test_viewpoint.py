import pytest
from speech_plan import plan_speech

PROFILE = {'id': 'elara', 'personality': 'Impulsive and fiercely loyal.', 'gender': 'Woman'}


@pytest.mark.parametrize('text', [
    'I shift my weight.', '*I shift my weight.*', 'My hand tightens around the lamp.',
    'It belongs to me.', 'That lantern is mine.', 'I’m ready.', "I've checked the route.",
    'We move north.', 'Our route is clear.', 'I steady myself.',
])
def test_unquoted_first_person_uses_the_characters_existing_voice(text):
    personal = plan_speech(text, PROFILE)
    dialogue = plan_speech('“Stay close.”', PROFILE)[0]
    assert all(s.speaker == 'character' for s in personal)
    assert all((s.style, s.seed, s.identity) == (dialogue.style, dialogue.seed, dialogue.identity) for s in personal)


def test_mixed_paragraphs_and_actions_are_routed_by_sentence():
    plan = plan_speech('The hall is dark. I shift my weight. She opens the door.\n*My hands shake.* “Stay close.”', PROFILE)
    assert [(s.speaker, s.text) for s in plan] == [
        ('narrator', 'The hall is dark.'), ('character', 'I shift my weight.'),
        ('narrator', 'She opens the door.'), ('character', 'My hands shake. Stay close.'),
    ]


@pytest.mark.parametrize('text', [
    '*She reads "I am ready. My route is clear." aloud.*',
    'The mine is closed.', 'The US envoy arrives.', 'Mira studies the CID.',
])
def test_reported_quotes_nouns_and_substrings_do_not_change_viewpoint(text):
    assert all(s.speaker == 'narrator' for s in plan_speech(text, PROFILE))


def test_narration_without_character_metadata_stays_with_narrator():
    assert all(s.speaker == 'narrator' for s in plan_speech('I shift my weight. “Stay close.”'))


@pytest.mark.parametrize('opening_quote', ['"', '“'])
def test_orphan_quote_before_miras_action_does_not_mask_her_viewpoint(opening_quote):
    text = (opening_quote + '*I tuck a stray strand of blonde hair behind my ear, my fingers lingering on my CID. '
            'The green glow makes my teeth ache; it smells of static and risk. '
            'I straighten my spine, ensuring my uniform remains immaculate.*\n\n'
            '"Ryn is correct about the pace. *I step closer to the board.* Amir, direct our attention to the Mill Road."')
    plan = plan_speech(text, PROFILE)
    dialogue = plan_speech('"Ryn is correct."', PROFILE)[0]
    assert plan
    assert all(s.speaker == 'character' for s in plan)
    assert all((s.style, s.seed, s.identity) == (dialogue.style, dialogue.seed, dialogue.identity) for s in plan)
    assert plan[0].text.startswith('I tuck a stray strand')
    assert 'C I D' in plan[0].text
    spoken = ' '.join(s.text for s in plan)
    assert 'I step closer to the board.' in spoken
    assert spoken.endswith('Mill Road.')


def test_orphan_action_quote_preserves_reported_quote_masking_and_third_person():
    plan = plan_speech('\"*She reads "I am ready. My route is clear." aloud.*\n\n“Wait here.”', PROFILE)
    assert plan[0].speaker == 'narrator'
    assert plan[-1].speaker == 'character'
    assert all(s.speaker == 'narrator' for s in plan[:-1])


def test_balanced_outer_quotation_is_still_character_dialogue():
    plan = plan_speech('"*She enters.* The hall is empty."', PROFILE)
    assert all(s.speaker == 'character' for s in plan)
