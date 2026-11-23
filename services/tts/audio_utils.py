"""PCM utilities for clean speaker handoffs."""
from array import array
import sys


def trim_pcm_tail(pcm: bytes, sample_rate: int, keep_seconds: float = .12) -> bytes:
    """Remove near-silent padding; retain 120 ms at endings, less for continuations."""
    samples = array('h')
    samples.frombytes(pcm)
    if sys.byteorder != 'little':
        samples.byteswap()
    end = len(samples)
    # About -54 dBFS; retain quiet speech and breaths, discard model padding.
    while end and abs(samples[end - 1]) < 64:
        end -= 1
    end = min(len(samples), end + max(1, int(sample_rate * keep_seconds)))
    return pcm[:end * 2]


# Keep the model's waveform intact. Tempo processing can color speech, so
# narration's unhurried cadence is designed at the source, with pauses here.
NARRATOR_PAUSE_SECONDS = .25


def pace_narration(pcm: bytes, sample_rate: int) -> bytes:
    """Retain a sentence-end pause without filtering or stretching the voice."""
    if not pcm:
        return pcm
    return pcm + b'\0\0' * round(sample_rate * NARRATOR_PAUSE_SECONDS)
