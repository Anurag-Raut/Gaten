#!/usr/bin/env python3
"""Regenerate the two feedback tones shipped in app/src/main/res/raw.

The tones are plain sine partials written with the stdlib `wave` module and encoded to OGG
Vorbis with ffmpeg, so the shipped assets stay reproducible and reviewable rather than
being opaque binaries. Both together are a few kilobytes.

    python3 tools/make_feedback_tones.py

`feedback_added` is the short two-note cue for a word landing in Anki; `feedback_done` is the
three-note rise for a finished review session.
"""

import math
import subprocess
import wave

RATE = 22050
RAW_DIR = "app/src/main/res/raw"


def note(freq, ms, amplitude=0.45):
    """One blipped sine: a 4 ms attack and a 5 ms release around an exponential decay.

    A bare sine starts and ends on a step, which clicks; those three envelopes are the whole
    difference between a cue and a pop.
    """
    total = int(RATE * ms / 1000)
    samples = []
    for i in range(total):
        t = i / RATE
        attack = min(1.0, i / (0.004 * RATE))
        release = min(1.0, (total - i) / (0.005 * RATE))
        decay = math.exp(-t / (ms / 1000 / 3))
        wave_sum = math.sin(2 * math.pi * freq * t) + 0.25 * math.sin(4 * math.pi * freq * t)
        samples.append(wave_sum * amplitude * attack * release * decay)
    return samples


def encode(name, samples):
    """Writes the samples to a temp WAV and encodes it to `res/raw/<name>.ogg`."""
    data = bytearray()
    for sample in samples:
        clamped = max(-1.0, min(1.0, sample))
        data += int(clamped * 32767).to_bytes(2, "little", signed=True)

    wav_path = f"/tmp/{name}.wav"
    with wave.open(wav_path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(bytes(data))

    ogg_path = f"{RAW_DIR}/{name}.ogg"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path,
         "-c:a", "libvorbis", "-b:a", "48k", "-ac", "1", ogg_path],
        check=True,
    )
    print(f"{ogg_path}: {len(data) / 2 / RATE * 1000:.0f} ms")


def main():
    # B5 -> E6: a rising interval reads as "done, it landed".
    encode("feedback_added", note(987.77, 60) + note(1318.51, 110))
    # C6 -> E6 -> G6: the same shape, stretched over a finished session.
    encode("feedback_done", note(1046.50, 130) + note(1318.51, 130) + note(1567.98, 200))


if __name__ == "__main__":
    main()
