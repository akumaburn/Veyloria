"""PCM utilities for clean speaker handoffs."""

# Trailing audio quieter than this, relative to the clip's loud speech, is model
# padding or room noise rather than speech. Ming continues a cloned voice from
# the tail of its reference, so a noisy tail would make every later line start
# from "finished" and stop after a few steps.
TAIL_FRAME_SECONDS = .02
TAIL_RELATIVE_LEVEL = .05  # about -26 dB below the clip's loud speech
TAIL_ABSOLUTE_LEVEL = 32.0  # about -60 dBFS


def trim_pcm_tail(pcm: bytes, sample_rate: int, keep_seconds: float = .12) -> bytes:
    """Remove the non-speech tail; retain 120 ms at endings, less for continuations."""
    import numpy as np
    samples = np.frombuffer(pcm[:len(pcm) - len(pcm) % 2], dtype='<i2').astype(np.float64)
    frame = max(1, int(sample_rate * TAIL_FRAME_SECONDS))
    frames = len(samples) // frame
    end = 0
    if frames:
        rms = np.sqrt(np.mean(samples[:frames * frame].reshape(frames, frame) ** 2, axis=1))
        floor = max(TAIL_ABSOLUTE_LEVEL, TAIL_RELATIVE_LEVEL * float(np.percentile(rms, 95)))
        audible = np.flatnonzero(rms >= floor)
        end = (int(audible[-1]) + 1) * frame if audible.size else 0
    end = min(len(samples), end + max(1, int(sample_rate * keep_seconds)))
    return pcm[:end * 2]


# Plausible speaking rates in characters per second, generous at both ends. A
# take outside them was cut off by an early stop or ran on past its text.
_RATES = {'zh': (1.5, 12.0), 'ja': (2.0, 16.0), 'ko': (2.0, 14.0)}
_DEFAULT_RATES = (6.0, 30.0)


def plausible_duration(text: str, seconds: float, language_id: str = 'en') -> bool:
    """Whether a take is long enough to contain its text and not far longer."""
    slowest, fastest = _RATES.get(language_id, _DEFAULT_RATES)
    characters = len(''.join(text.split()))
    return characters / fastest <= seconds <= characters / slowest + 1.0


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
    if frame <= longest + 1 or len(samples) < frame:
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
        # A maximum on the edge of the search range is no pitch period: breath
        # and fricatives correlate most at the shortest lag.
        is_peak = correlation[lag] > correlation[lag - 1] and correlation[lag] >= correlation[lag + 1]
        if is_peak and correlation[lag] / correlation[0] >= .5:  # clearly periodic, i.e. voiced
            pitches.append(sample_rate / lag)
    return float(np.median(pitches)) if pitches else None


def pitch_mismatch(pcm: bytes, sample_rate: int, voice_gender: str) -> float:
    """How far, in Hz, a take's pitch lies outside the requested presentation's range."""
    pitch = median_pitch(pcm, sample_rate) if voice_gender in {'female', 'male'} else None
    if pitch is None:
        return 0.0
    if voice_gender == 'female':
        return max(0.0, FEMALE_MIN_PITCH_HZ - pitch)
    return max(0.0, pitch - MALE_MAX_PITCH_HZ)


# Every voice is brought to the same active speech level. Ming designs some
# voices far quieter than others; within a voice, delivery stays as generated.
TARGET_SPEECH_DBFS = -20.0
PEAK_CEILING_DBFS = -1.0
_ACTIVE_RANGE_DB = 35.0  # frames this far below the loudest count as speech


def speech_level(pcm: bytes, sample_rate: int) -> float | None:
    """Mean power of the speaking frames in dBFS, ignoring pauses and silence."""
    import numpy as np
    samples = np.frombuffer(pcm[:len(pcm) - len(pcm) % 2], dtype='<i2').astype(np.float64) / 32768
    frame = max(1, sample_rate // 100)
    frames = len(samples) // frame
    if not frames:
        return None
    power = np.mean(samples[:frames * frame].reshape(frames, frame) ** 2, axis=1)
    if power.max() <= 0:
        return None
    active = power[power >= power.max() * 10 ** (-_ACTIVE_RANGE_DB / 10)]
    return float(10 * np.log10(active.mean()))


def normalize_level(pcm: bytes, sample_rate: int, voice_level: float | None) -> bytes:
    """Scale a take so its voice's baseline sits at the target level, without clipping."""
    import numpy as np
    if voice_level is None or not pcm:
        return pcm
    samples = np.frombuffer(pcm[:len(pcm) - len(pcm) % 2], dtype='<i2').astype(np.float64)
    gain = 10 ** ((TARGET_SPEECH_DBFS - voice_level) / 20)
    peak = np.abs(samples).max()
    if peak:
        gain = min(gain, 32767 * 10 ** (PEAK_CEILING_DBFS / 20) / peak)
    return np.round(samples * gain).clip(-32768, 32767).astype('<i2').tobytes()
