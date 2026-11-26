import struct

from audio_utils import plausible_duration, trim_pcm_tail


def test_removes_long_model_padding_and_preserves_natural_pause():
    pcm = struct.pack('<h', -2000) * 1000 + b'\0\0' * 24000
    assert len(trim_pcm_tail(pcm, 1000)) == 1120 * 2


def test_short_pause_and_quiet_speech_remain_intact():
    pcm = struct.pack('<h', 80) * 1000 + b'\0\0' * 50
    assert trim_pcm_tail(pcm, 1000) == pcm


def test_silent_generation_still_produces_a_playable_buffer():
    assert trim_pcm_tail(b'\0\0' * 24000, 1000) == b'\0\0' * 120


def test_low_level_noise_after_speech_is_trimmed_like_silence():
    # Speech at about -24 dBFS, then five seconds of faint hiss that never
    # quite reaches digital silence: Ming would continue from the hiss.
    speech = b''.join(struct.pack('<h', 2000 if n % 2 else -2000) for n in range(1000))
    hiss = b''.join(struct.pack('<h', 70 if n % 3 else -90) for n in range(5000))
    assert len(trim_pcm_tail(speech + hiss, 1000)) == 1120 * 2


def test_a_soft_final_syllable_is_kept():
    speech = struct.pack('<h', 3000) * 1000
    fade = struct.pack('<h', 300) * 200  # -20 dB below the speech: still speech
    assert len(trim_pcm_tail(speech + fade + b'\0\0' * 2000, 1000)) == 1320 * 2


def test_a_take_is_plausible_only_when_its_length_suits_its_text():
    line = 'The open square is no place for a calibration log, Elara.'
    assert plausible_duration(line, 2.9)
    assert not plausible_duration(line, .12)  # stopped after a few decoder steps
    assert not plausible_duration(line, 14.0)  # ran on long past the text
    assert plausible_duration('Hm.', .3)
    assert plausible_duration('冰川在漫长岁月中缓缓移动。', 3.0, 'zh')


import math

import pytest

from audio_utils import median_pitch, pitch_mismatch


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
    assert pitch_mismatch(silence, 24000, 'female') == 0


def test_pitch_mismatch_measures_distance_from_the_requested_range():
    low, high = voiced(130), voiced(210)
    assert pitch_mismatch(low, 24000, 'female') == pytest.approx(25, abs=5)
    assert pitch_mismatch(high, 24000, 'female') == 0
    assert pitch_mismatch(low, 24000, 'male') == 0
    assert pitch_mismatch(high, 24000, 'male') == pytest.approx(35, abs=7)
    assert pitch_mismatch(low, 24000, '') == pitch_mismatch(high, 24000, '') == 0


def test_breath_noise_is_not_mistaken_for_a_high_pitch():
    import numpy as np
    rng = np.random.default_rng(7)
    # Low-passed noise correlates most at the shortest lag, like breath or hiss.
    breath = np.convolve(rng.normal(0, 4000, 24000), np.ones(40) / 40, 'same').astype('<i2').tobytes()
    assert median_pitch(breath, 24000) is None
    assert median_pitch(voiced(110) + breath, 24000) == pytest.approx(110, rel=.03)


def test_quiet_and_loud_voices_reach_the_same_speech_level():
    from audio_utils import TARGET_SPEECH_DBFS, normalize_level, speech_level
    quiet, loud = voiced(130), voiced(210)
    quiet = b''.join(struct.pack('<h', s // 8) for s in struct.unpack(f'<{len(quiet) // 2}h', quiet))
    for take in (quiet, loud):
        level = speech_level(take, 24000)
        assert speech_level(normalize_level(take, 24000, level), 24000) == pytest.approx(TARGET_SPEECH_DBFS, abs=.5)


def test_a_voice_baseline_keeps_each_lines_own_dynamics():
    from audio_utils import normalize_level, speech_level
    line = voiced(130)
    softer = b''.join(struct.pack('<h', s // 2) for s in struct.unpack(f'<{len(line) // 2}h', line))
    baseline = speech_level(line, 24000)
    difference = speech_level(normalize_level(line, 24000, baseline), 24000) - speech_level(
        normalize_level(softer, 24000, baseline), 24000)
    assert difference == pytest.approx(6.0, abs=.3)  # the softer line stays 6 dB softer


def test_normalizing_never_clips():
    from audio_utils import PEAK_CEILING_DBFS, normalize_level
    spiky = voiced(130) + struct.pack('<h', 20000) * 10
    louder = normalize_level(spiky, 24000, -40.0)
    peak = max(abs(s) for s in struct.unpack(f'<{len(louder) // 2}h', louder))
    assert peak <= 32767 * 10 ** (PEAK_CEILING_DBFS / 20) + 1
    assert normalize_level(b'', 24000, -30.0) == b'' and normalize_level(spiky, 24000, None) == spiky
