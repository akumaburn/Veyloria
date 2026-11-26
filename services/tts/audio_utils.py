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


# Median speaking pitch bounds for a designed voice to count as its intended
# presentation. Typical adult ranges are about 85-180 Hz (male) and 165-255 Hz
# (female); the bounds leave room for deep female and light male voices.
FEMALE_MIN_PITCH_HZ = 155.0
MALE_MAX_PITCH_HZ = 175.0
_PITCH_RANGE_HZ = (60.0, 450.0)


def median_pitch(pcm: bytes, sample_rate: int) -> float | None:
    """Median fundamental frequency of the voiced frames, or None when nothing is voiced."""
    import numpy as np
    samples = np.frombuffer(pcm[:len(pcm) - len(pcm) % 2], dtype='<i2').astype(np.float64)
    frame, hop = int(sample_rate * .04), int(sample_rate * .01)
    shortest, longest = int(sample_rate / _PITCH_RANGE_HZ[1]), int(sample_rate / _PITCH_RANGE_HZ[0])
    if frame <= longest or len(samples) < frame:
        return None
    window = np.hanning(frame)
    pitches = []
    for start in range(0, len(samples) - frame + 1, hop):
        chunk = samples[start:start + frame]
        chunk = chunk - chunk.mean()
        if np.sqrt(np.mean(chunk ** 2)) < 300:  # about -40 dBFS: silence and breaths
            continue
        spectrum = np.fft.rfft(chunk * window, 2 * frame)
        correlation = np.fft.irfft(np.abs(spectrum) ** 2)[:frame]
        if correlation[0] <= 0:
            continue
        lag = shortest + int(np.argmax(correlation[shortest:longest + 1]))
        if correlation[lag] / correlation[0] >= .5:  # clearly periodic, i.e. voiced
            pitches.append(sample_rate / lag)
    return float(np.median(pitches)) if pitches else None


def matches_voice_gender(pcm: bytes, sample_rate: int, voice_gender: str) -> bool:
    """False only when a measurable pitch clearly contradicts the requested presentation."""
    if voice_gender not in {'female', 'male'}:
        return True
    pitch = median_pitch(pcm, sample_rate)
    if pitch is None:
        return True
    return pitch >= FEMALE_MIN_PITCH_HZ if voice_gender == 'female' else pitch <= MALE_MAX_PITCH_HZ
