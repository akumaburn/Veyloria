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
