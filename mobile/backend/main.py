"""DubStudio KH Pro Mobile — API server (Whisper + optional speaker diarization + Khmer translation).

This service never claims speaker gender or actual character identities from audio.
Do not expose without HTTPS and DUBSTUDIO_API_TOKEN.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
import uuid
from functools import lru_cache
from pathlib import Path
from threading import Lock
from typing import Any

import requests
from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import PlainTextResponse, FileResponse

APP_DIR = Path(os.environ.get("DUBSTUDIO_DATA_DIR", "./data")).resolve()
APP_DIR.mkdir(parents=True, exist_ok=True)
MAX_BYTES = int(os.getenv("MAX_VIDEO_MB", "300")) * 1024 * 1024
RETENTION_SECONDS = int(os.getenv("JOB_RETENTION_HOURS", "24")) * 3600
TOKEN = os.environ.get("DUBSTUDIO_API_TOKEN", "")
WHISPER_SIZE = os.environ.get("WHISPER_MODEL", "small")
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://127.0.0.1:11434/api/generate")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "qwen2.5:3b")
ENABLE_DEMUCS = os.getenv("ENABLE_DEMUCS", "0") == "1"
ENABLE_VOXCPM = os.getenv("ENABLE_VOXCPM", "0") == "1"
MAX_REFERENCE_BYTES = 15 * 1024 * 1024
ENABLE_DIARIZATION = os.getenv("ENABLE_DIARIZATION", "0") == "1"
HF_TOKEN = os.getenv("HF_TOKEN", "")

app = FastAPI(title="DubStudio KH Pro Mobile API", version="1.0.0")
file_lock = Lock()
job_execution_lock = Lock()  # one heavy AI job at a time on modest servers


def require_token(authorization: str | None = Header(default=None)) -> None:
    # Require authentication even for local use to avoid accidental public deployment.
    import secrets
    if not TOKEN:
        raise HTTPException(503, "Server API token not configured. Set DUBSTUDIO_API_TOKEN.")
    if not authorization or not authorization.startswith("Bearer ") or not secrets.compare_digest(authorization[7:], TOKEN):
        raise HTTPException(401, "Invalid API token")


def job_dir(job_id: str) -> Path:
    if not re.fullmatch(r"[0-9a-f]{32}", job_id):
        raise HTTPException(404, "Job not found")
    folder = APP_DIR / job_id
    if not folder.is_dir():
        raise HTTPException(404, "Job not found")
    return folder


def write_json_atomic(path: Path, data: dict[str, Any]) -> None:
    temp = path.with_name(path.name + ".tmp")
    with file_lock:
        temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, path)


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def progress(job_id: str, stage: str, percentage: int, message: str, **extras: Any) -> None:
    folder = job_dir(job_id)
    p = folder / "status.json"
    old = read_json(p) if p.exists() else {}
    old.update({"job_id": job_id, "stage": stage, "percent": max(0, min(100, int(percentage))),
                "message": message, "updated_at": int(time.time())})
    old.update(extras)
    write_json_atomic(p, old)


def cleanup_old_jobs() -> None:
    now = time.time()
    for folder in APP_DIR.iterdir():
        if folder.is_dir() and re.fullmatch(r"[0-9a-f]{32}", folder.name) and now - folder.stat().st_mtime > RETENTION_SECONDS:
            shutil.rmtree(folder, ignore_errors=True)


@app.get("/api/health", dependencies=[Depends(require_token)])
def health():
    return {"ok": True, "whisper_model": WHISPER_SIZE,
            "speaker_scan_configured": ENABLE_DIARIZATION and bool(HF_TOKEN),
            "translation_model": OLLAMA_MODEL, "demucs_enabled": ENABLE_DEMUCS,
            "voxcpm_enabled": ENABLE_VOXCPM, "voice_clone_requires_consent": True}


@app.post("/api/jobs", dependencies=[Depends(require_token)])
async def create_job(background_tasks: BackgroundTasks, video: UploadFile = File(...),
                     source_language: str = Form("auto"), scan_speakers: str = Form("true"),
                     isolate_vocals: str = Form("false")):
    if video.content_type and not (video.content_type.startswith("video/") or video.content_type == "application/octet-stream"):
        raise HTTPException(400, "Please upload a video")
    if not (source_language == "auto" or re.fullmatch(r"[a-z]{2,3}", source_language)):
        raise HTTPException(400, "Invalid source language")
    if isolate_vocals.lower() == "true" and not ENABLE_DEMUCS:
        raise HTTPException(503, "Demucs is not enabled on the backend")
    cleanup_old_jobs()
    jid = uuid.uuid4().hex
    folder = APP_DIR / jid
    folder.mkdir(parents=True)
    dest = folder / "uploaded_video"
    total = 0
    try:
        with dest.open("wb") as output:
            while part := await video.read(1024 * 1024):
                total += len(part)
                if total > MAX_BYTES:
                    raise HTTPException(413, f"File too large (max {MAX_BYTES // (1024*1024)} MB)")
                output.write(part)
        if not total:
            raise HTTPException(400, "Empty video")
    except Exception:
        shutil.rmtree(folder, ignore_errors=True)
        raise
    finally:
        await video.close()
    write_json_atomic(folder / "status.json", {"job_id": jid, "stage": "queued", "percent": 0,
                                               "message": "Video uploaded; queued", "updated_at": int(time.time())})
    background_tasks.add_task(process_job, jid, source_language, scan_speakers.lower() == "true", isolate_vocals.lower() == "true")
    return {"job_id": jid}


@app.get("/api/jobs/{job_id}", dependencies=[Depends(require_token)])
def get_job(job_id: str):
    return read_json(job_dir(job_id) / "status.json")


@app.get("/api/jobs/{job_id}/result", dependencies=[Depends(require_token)])
def get_result(job_id: str):
    file = job_dir(job_id) / "result.json"
    if not file.exists():
        raise HTTPException(409, "Original subtitles are not generated yet")
    return read_json(file)


@app.get("/api/jobs/{job_id}/srt", response_class=PlainTextResponse, dependencies=[Depends(require_token)])
def get_srt(job_id: str, kind: str = "km"):
    if kind not in ("original", "km"):
        raise HTTPException(400, "kind must be original or km")
    folder = job_dir(job_id)
    file = folder / ("original.srt" if kind == "original" else "khmer.srt")
    if not file.exists():
        raise HTTPException(409, "Requested SRT is not ready")
    return PlainTextResponse(file.read_text(encoding="utf-8"), media_type="text/plain; charset=utf-8")


@app.post("/api/jobs/{job_id}/speakers", dependencies=[Depends(require_token)])
def rename_speakers(job_id: str, mappings: dict[str, str]):
    folder = job_dir(job_id)
    result_file = folder / "result.json"
    if not result_file.exists():
        raise HTTPException(409, "Subtitles are not ready")
    result = read_json(result_file)
    allowed = {s["speaker"] for s in result["subtitles"]}
    for key, name in mappings.items():
        if key not in allowed or not isinstance(name, str) or len(name.strip()) > 48 or not name.strip():
            raise HTTPException(400, "Invalid speaker label")
    # Store a map keyed by stable speaker IDs instead of replacing them, so future edits remain possible.
    existing = result.get("speaker_names", {})
    existing.update({k: v.strip() for k, v in mappings.items()})
    result["speaker_names"] = existing
    write_json_atomic(result_file, result)
    write_srt_files(folder, result)
    return result


def srt_stamp(sec: float) -> str:
    millis = max(0, int(round(sec * 1000)))
    h, left = divmod(millis, 3600_000)
    m, left = divmod(left, 60_000)
    s, ms = divmod(left, 1000)
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


def make_srt(subs: list[dict[str, Any]], lang: str, speaker_names: dict[str, str] | None = None) -> str:
    speaker_names = speaker_names or {}
    lines: list[str] = []
    for i, item in enumerate(subs, 1):
        content = (item.get("khmer") if lang == "km" else item.get("original")) or ""
        if lang == "km" and not content:
            continue  # never silently substitute source-language speech as Khmer
        name = speaker_names.get(item["speaker"], item["speaker"])
        prefix = f"[{name}] " if name else ""
        lines.append(f"{i}\n{srt_stamp(item['start'])} --> {srt_stamp(item['end'])}\n{prefix}{content}\n")
    return "\n".join(lines)


def write_srt_files(folder: Path, result: dict[str, Any]):
    subs = result.get("subtitles", [])
    names = result.get("speaker_names", {})
    (folder / "original.srt").write_text(make_srt(subs, "original", names), encoding="utf-8")
    if subs and all(x.get("khmer") for x in subs):
        (folder / "khmer.srt").write_text(make_srt(subs, "km", names), encoding="utf-8")


@lru_cache(maxsize=1)
def whisper_model():
    from faster_whisper import WhisperModel
    return WhisperModel(WHISPER_SIZE, device=os.getenv("WHISPER_DEVICE", "cpu"),
                        compute_type=os.getenv("WHISPER_COMPUTE_TYPE", "int8"))


def extract_audio(folder: Path) -> Path:
    dst = folder / "speech.wav"
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(folder / "uploaded_video"),
               "-vn", "-ac", "1", "-ar", "16000", str(dst)]
    subprocess.run(command, check=True, timeout=3600, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return dst


def transcribe_audio(audio: Path, language: str) -> tuple[list[dict[str, Any]], str]:
    segments, info = whisper_model().transcribe(str(audio), language=None if language == "auto" else language,
                                                 vad_filter=True, beam_size=3)
    subs = []
    for segment in segments:
        spoken = segment.text.strip()
        if spoken:
            subs.append({"start": round(float(segment.start), 3), "end": round(float(segment.end), 3),
                         "speaker": "SPEAKER_01", "original": spoken, "khmer": ""})
    if not subs:
        raise RuntimeError("No spoken words found. Check the video's audio.")
    return subs, (info.language or "unknown")


def scan_speakers(audio: Path, subtitles: list[dict[str, Any]]) -> bool:
    """Optional, server-side pyannote diarization; labels are anonymous, not character names or genders."""
    if not (ENABLE_DIARIZATION and HF_TOKEN):
        return False
    from pyannote.audio import Pipeline
    try:
        pipeline = Pipeline.from_pretrained("pyannote/speaker-diarization-3.1", token=HF_TOKEN)
    except TypeError:  # older pyannote APIs
        pipeline = Pipeline.from_pretrained("pyannote/speaker-diarization-3.1", use_auth_token=HF_TOKEN)
    diarization = pipeline(str(audio))
    intervals: list[tuple[float, float, str]] = []
    for turn, _, speaker in diarization.itertracks(yield_label=True):
        intervals.append((float(turn.start), float(turn.end), str(speaker)))
    if not intervals:
        return False
    for sub in subtitles:
        a, b = sub["start"], sub["end"]
        scores: dict[str, float] = {}
        for t0, t1, speaker in intervals:
            overlap = max(0, min(b, t1) - max(a, t0))
            scores[speaker] = scores.get(speaker, 0) + overlap
        if scores and max(scores.values()) > 0:
            sub["speaker"] = max(scores, key=scores.get)
    return True


def translate_to_khmer(text: str, source_language: str) -> str:
    if source_language == "km":
        return text
    prompt = ("Translate the following spoken dialogue into natural, accurate Khmer (ភាសាខ្មែរ). "
              "Preserve names and meaning; use short lines suitable for subtitle dubbing. "
              "Output ONLY the Khmer translation, with no labels, no quotation marks and no explanation.\n"
              f"Source language: {source_language}\nDialogue: {text}\nKhmer translation:")
    response = requests.post(OLLAMA_URL, json={"model": OLLAMA_MODEL, "prompt": prompt, "stream": False,
                                                    "options": {"temperature": 0.1}}, timeout=240)
    response.raise_for_status()
    result = response.json().get("response", "").strip()
    if not result:
        raise RuntimeError("Translation returned an empty response")
    return result.strip('“”" ')


def process_job(job_id: str, language: str, want_speakers: bool, isolate_vocals: bool = False) -> None:
    # Keep model RAM usage bounded, processing jobs serially.
    with job_execution_lock:
        _run_job(job_id, language, want_speakers, isolate_vocals)


def _run_job(job_id: str, language: str, want_speakers: bool, isolate_vocals: bool = False) -> None:
    folder = job_dir(job_id)
    diarization_warning = ""
    try:
        progress(job_id, "audio", 5, "Extracting audio from video...")
        audio = extract_audio(folder)
        if isolate_vocals:
            progress(job_id, "demucs", 8, "Separating vocals with Demucs...")
            audio = isolate_dialogue(audio, folder)
        progress(job_id, "transcribe", 12, "Generating original subtitles...")
        subtitles, detected_lang = transcribe_audio(audio, language)
        result = {"detected_language": detected_lang, "subtitles": subtitles, "speaker_names": {},
                  "scan_status": "not_requested" if not want_speakers else "not_configured"}
        write_json_atomic(folder / "result.json", result)
        write_srt_files(folder, result)
        progress(job_id, "transcribe", 45, f"Created {len(subtitles)} subtitle lines", original_ready=True)
        if want_speakers:
            if not (ENABLE_DIARIZATION and HF_TOKEN):
                diarization_warning = "Speaker diarization not configured; all lines are SPEAKER_01."
            else:
                try:
                    progress(job_id, "scan", 49, "Scanning who speaks when...")
                    found = scan_speakers(audio, subtitles)
                    result["scan_status"] = "done" if found else "not_detected"
                    if not found:
                        diarization_warning = "No distinct speakers detected. Please verify manually."
                except Exception as err:
                    result["scan_status"] = "unavailable"
                    diarization_warning = f"Speaker scan unavailable: {str(err)[:200]}"
            write_json_atomic(folder / "result.json", result)
            write_srt_files(folder, result)
        progress(job_id, "translate", 55, "Translating subtitles into Khmer...", warning=diarization_warning)
        for i, entry in enumerate(subtitles):
            entry["khmer"] = translate_to_khmer(entry["original"], detected_lang)
            # Save partial translations so a failure never loses completed work.
            write_json_atomic(folder / "result.json", result)
            progress(job_id, "translate", 55 + int(44 * (i+1)/len(subtitles)),
                     f"Translated {i+1}/{len(subtitles)} subtitle lines", warning=diarization_warning)
        write_srt_files(folder, result)
        progress(job_id, "done", 100, "Khmer subtitles are ready", warning=diarization_warning,
                 original_ready=True, khmer_ready=True)
    except Exception as err:
        # preserve original result if available, including partial translations
        progress(job_id, "failed", 0, str(err)[:400], original_ready=(folder / "original.srt").exists(),
                 khmer_ready=(folder / "khmer.srt").exists(), warning=diarization_warning)


# -- Optional AI audio pipeline --
def isolate_dialogue(audio: Path, folder: Path) -> Path:
    """Demucs separation in a subprocess: isolate vocals, do not discard original media."""
    if not ENABLE_DEMUCS:
        raise RuntimeError("Demucs not enabled. Set ENABLE_DEMUCS=1 and install demucs.")
    output = folder / "demucs_output"
    subprocess.run(["demucs", "--two-stems=vocals", "--name", "htdemucs",
                    "--out", str(output), str(audio)], check=True, timeout=3600,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    candidates = list(output.glob("**/vocals.wav"))
    if len(candidates) != 1:
        raise RuntimeError("Demucs did not produce exactly one vocals.wav")
    mono = folder / "isolated_speech.wav"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(candidates[0]),
                    "-ac", "1", "-ar", "16000", str(mono)], check=True, timeout=300,
                   stderr=subprocess.PIPE)
    return mono


def _available_speakers(folder: Path) -> set[str]:
    return {x["speaker"] for x in read_json(folder / "result.json").get("subtitles", [])}


@app.post("/api/jobs/{job_id}/voice-reference", dependencies=[Depends(require_token)])
async def upload_voice_reference(job_id: str, speaker: str = Form(...), consent: str = Form("false"),
                                 audio: UploadFile = File(...)):
    """Consent-gated, job-local voice-reference storage, deleted with the job."""
    folder = job_dir(job_id)
    if not (folder / "result.json").exists():
        raise HTTPException(409, "Generate subtitles first")
    if speaker not in _available_speakers(folder):
        raise HTTPException(400, "Unknown speaker ID")
    if consent != "true":
        raise HTTPException(400, "Explicit voice-owner permission is required")
    if audio.content_type and not (audio.content_type.startswith("audio/") or audio.content_type == "application/octet-stream"):
        raise HTTPException(400, "Upload an audio recording")
    temp = folder / (uuid.uuid4().hex + ".input")
    size = 0
    try:
        with temp.open("wb") as out:
            while chunk := await audio.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_REFERENCE_BYTES:
                    raise HTTPException(413, "Audio must be 15MB or less")
                out.write(chunk)
        if size == 0:
            raise HTTPException(400, "Empty audio")
        references = folder / "voice_references"
        references.mkdir(exist_ok=True)
        # Stable, allow-listed speaker identifier; never trust client-supplied path text.
        stable = _available_speakers(folder)
        index = sorted(stable).index(speaker)
        destination = references / f"voice_{index:03d}.wav"
        conversion = destination.with_suffix('.partial.wav')
        try:
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(temp),
                            "-t", "45", "-vn", "-ac", "1", "-ar", "24000", str(conversion)],
                           check=True, timeout=100, stderr=subprocess.PIPE)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            raise HTTPException(400, "Invalid audio recording; use a clear WAV/MP3/M4A clip")
        if not conversion.exists() or conversion.stat().st_size < 1000:
            raise HTTPException(400, "Reference audio too short or invalid")
        conversion.replace(destination)
        mapping_file = folder / "voice_references.json"
        mapping = read_json(mapping_file) if mapping_file.exists() else {}
        mapping[speaker] = destination.name
        write_json_atomic(mapping_file, mapping)
        return {"speaker": speaker, "uploaded": True, "consent_received": True}
    finally:
        temp.unlink(missing_ok=True)
        await audio.close()
        if 'conversion' in locals():
            conversion.unlink(missing_ok=True)


@app.post("/api/jobs/{job_id}/generate-voice", dependencies=[Depends(require_token)])
def generate_voice(job_id: str, background_tasks: BackgroundTasks, styles: dict[str, str] | None = None):
    folder = job_dir(job_id)
    if not ENABLE_VOXCPM:
        raise HTTPException(503, "VoxCPM2 not enabled on server; install optional package and set ENABLE_VOXCPM=1")
    result_file = folder / "result.json"
    if not result_file.exists():
        raise HTTPException(409, "Generate subtitles first")
    result = read_json(result_file)
    subtitles = result.get("subtitles", [])
    if not subtitles or any(not x.get("khmer", "").strip() for x in subtitles):
        raise HTTPException(409, "Complete Khmer translation before generating voice")
    current_status = read_json(folder / "status.json")
    if current_status.get("stage") not in ("done", "voice_done", "voice_failed"):
        raise HTTPException(409, "Processing not finished")
    allowed = {x["speaker"] for x in subtitles}
    styles = styles or {}
    if any(k not in allowed or v not in ("neutral", "male", "female") for k, v in styles.items()):
        raise HTTPException(400, "Unknown speaker or style")
    write_json_atomic(folder / "voice_styles.json", styles)
    progress(job_id, "voice", 0, "Preparing VoxCPM2 voice synthesis...", audio_ready=False)
    background_tasks.add_task(process_voice_job, job_id)
    return {"started": True, "job_id": job_id}


def process_voice_job(job_id: str) -> None:
    with job_execution_lock:
        folder = job_dir(job_id)
        try:
            from voice_pipeline import generate_voice_track
            result = read_json(folder / "result.json")
            ref_file = folder / "voice_references.json"
            references = read_json(ref_file) if ref_file.exists() else {}
            styles = read_json(folder / "voice_styles.json")
            def notify(done, total):
                progress(job_id, "voice", int(done / max(1, total) * 100),
                         f"Generating AI voice {done}/{total}", audio_ready=False)
            warnings = generate_voice_track(folder, result["subtitles"], references, styles, notify)
            progress(job_id, "voice_done", 100, "AI voice WAV ready", audio_ready=True,
                     voice_warnings=warnings)
        except Exception as e:
            (folder / "dubbed_track.wav").unlink(missing_ok=True)
            progress(job_id, "voice_failed", 0, str(e)[:400], audio_ready=False)


@app.get("/api/jobs/{job_id}/voice.wav", dependencies=[Depends(require_token)])
def download_voice(job_id: str):
    folder = job_dir(job_id)
    output = folder / "dubbed_track.wav"
    if not output.exists():
        raise HTTPException(409, "AI voice not ready")
    return FileResponse(str(output), media_type="audio/wav", filename="DubStudio_Khmer_AI_Voice.wav")
