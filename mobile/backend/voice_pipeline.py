"""VoxCPM2 speech synthesis adapter. Heavy models stay on server, not Android."""
from __future__ import annotations
import os
from functools import lru_cache
from pathlib import Path
import numpy as np
import soundfile as sf


@lru_cache(maxsize=1)
def _model():
    from voxcpm import VoxCPM
    return VoxCPM.from_pretrained(os.getenv("VOXCPM_MODEL", "openbmb/VoxCPM2"), load_denoiser=False)


def _speak(text: str, reference: Path | None, style: str):
    model = _model()
    guidance = {"neutral": "Native Khmer speaker, clear conversational speech.",
                "male": "Adult male voice, native Khmer speaker, natural and clear.",
                "female": "Adult female voice, native Khmer speaker, natural and clear."}.get(style, "")
    kwargs = {"text": text if reference else f"({guidance}){text}",
              "cfg_value": 2.0, "inference_timesteps": 10}
    if reference:
        kwargs["reference_wav_path"] = str(reference)
    return np.asarray(model.generate(**kwargs), dtype=np.float32).flatten(), model.tts_model.sample_rate


def generate_voice_track(folder: Path, lines: list[dict], references: dict, styles: dict, notify):
    """Generate per speaker; align line starts to SRT; report overlap rather than trimming spoken words."""
    target_rate = 24000
    if not lines:
        raise ValueError("No translated subtitles")
    max_seconds = max(float(line["end"]) for line in lines) + 15
    if max_seconds > 4 * 3600:
        raise ValueError("Video too long for a single voice render")
    mixed = np.zeros(int(max_seconds * target_rate) + 1, dtype=np.float32)
    warnings = []
    output_dir = folder / "generated_clips"
    output_dir.mkdir(exist_ok=True)
    for index, line in enumerate(lines):
        speaker = line["speaker"]
        reference_name = references.get(speaker)
        ref = folder / "voice_references" / reference_name if reference_name else None
        if ref and (not ref.is_file() or ref.parent != folder / "voice_references"):
            raise ValueError("Voice reference not found")
        text = line.get("khmer", "").strip()
        if not text:
            raise ValueError("Untranslated subtitle found")
        audio, rate = _speak(text, ref, styles.get(speaker, "neutral"))
        if len(audio) == 0 or not np.isfinite(audio).all():
            raise RuntimeError(f"Empty or invalid audio for subtitle #{index + 1}")
        # Explicitly resample to 24k; don't play audio at the wrong rate.
        if rate != target_rate:
            from scipy.signal import resample_poly
            from math import gcd
            divisor = gcd(int(rate), target_rate)
            audio = resample_poly(audio, target_rate//divisor, int(rate)//divisor).astype(np.float32)
        sf.write(output_dir / f"line_{index+1:04d}.wav", audio, target_rate)
        start = max(0, int(float(line["start"]) * target_rate))
        end = start + len(audio)
        if end > len(mixed):
            mixed = np.pad(mixed, (0, end-len(mixed)))
        mixed[start:end] += audio
        slot = max(0, float(line["end"]) - float(line["start"]))
        if len(audio) / target_rate > slot + 0.2:
            warnings.append(f"Subtitle #{index+1} runs longer than its SRT slot; adjust text/timing")
        notify(index+1, len(lines))
    mixed = np.clip(mixed, -0.98, 0.98)
    temp = folder / "dubbed_track.partial.wav"
    sf.write(temp, mixed, target_rate)
    temp.replace(folder / "dubbed_track.wav")
    return warnings[:30]
