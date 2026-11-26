import struct

from audio_utils import trim_pcm_tail


def test_removes_long_model_padding_and_preserves_natural_pause():
    pcm = struct.pack('<h', -2000) * 1000 + b'\0\0' * 24000
    assert len(trim_pcm_tail(pcm, 1000)) == 1120 * 2


def test_short_pause_and_quiet_speech_remain_intact():
    pcm = struct.pack('<h', 80) * 1000 + b'\0\0' * 50
    assert trim_pcm_tail(pcm, 1000) == pcm


def test_silent_generation_still_produces_a_playable_buffer():
    assert trim_pcm_tail(b'\0\0' * 24000, 1000) == b'\0\0' * 120


import math

import pytest

from audio_utils import matches_voice_gender, median_pitch


def voiced(pitch_hz: float, seconds: float = 1.0, rate: int = 24000) -> bytes:
    """A harmonic-rich periodic tone, like a sustained vowel at the given pitch."""
    return b''.join(struct.pack('<h', int(6000 * sum(
        math.sin(2 * math.pi * pitch_hz * k * n / rate) / k for k in range(1, 6))))
        for n in range(int(seconds * rate)))


@pytest.mark.parametrize('pitch', [95, 130, 210, 260])
def test_median_pitch_measures_the_fundamental(pitch):
    assert median_pitch(voiced(pitch), 24000) == pytest.approx(pitch, rel=.03)


def test_silence_has_no_pitch_and_never_blocks_a_voice():
    silence = b'\0\0' * 24000
    assert median_pitch(silence, 24000) is None
    assert matches_voice_gender(silence, 24000, 'female')


def test_a_low_voice_is_rejected_only_for_a_female_brief():
    low, high = voiced(130), voiced(210)
    assert not matches_voice_gender(low, 24000, 'female')
    assert matches_voice_gender(high, 24000, 'female')
    assert matches_voice_gender(low, 24000, 'male')
    assert not matches_voice_gender(high, 24000, 'male')
    assert matches_voice_gender(low, 24000, '') and matches_voice_gender(high, 24000, '')
