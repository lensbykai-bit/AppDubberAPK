DubStudio KH Pro Mobile v1.1 — Faster-Whisper + Demucs + VoxCPM2 + Voice Clone

1) Upload Video: Android ជ្រើសវីដេអូពីក្នុងទូរស័ព្ទ។
2) Faster-Whisper: បង្កើត SRT ភាសាដើម (ត្រូវការម៉ាស៊ីន AI Backend)។
3) Demucs (optional): បំបែកសំឡេងមនុស្សពីភ្លេង ដើម្បីជួយឱ្យការស្កេននិង transcription កាន់តែងាយ។
4) Speaker Scan (optional): សម្គាល់ថានរណានិយាយនៅពេលណា; មិនស្គាល់ឈ្មោះពិត ឬភេទតួអង្គដោយស្វ័យប្រវត្តិទេ។
5) Ollama: បកប្រែ Subtitle ជាភាសាខ្មែរ។
6) VoxCPM2: Generate Khmer Voice WAV ពី Subtitle ខ្មែរ។
7) Voice Clone (optional): ជ្រើស Speaker, យល់ព្រមជាមុនថាមានការអនុញ្ញាតពីម្ចាស់សំឡេង, upload WAV/MP3/M4A reference ≤15MB។
8) Listen/Save: ស្តាប់សំឡេងដែលបង្កើត និង Save WAV លើ Android។

សំខាន់៖ នេះជាគម្រោង Android និង Backend។ ម៉ូដែល AI មិនដំណើរការលើទូរស័ព្ទទេ; ត្រូវមាន Cloud Server ដែលតម្លើង GPU និង FFmpeg ឬម៉ាស៊ីន Server ដែលមាន RAM គ្រប់គ្រាន់។ គ្មាន Backend URL/Token ដែលបានដាក់ Deploy មកជាស្រេចទេ។

Backend:
- Python 3.11, ffmpeg, uvicorn, pip install -r requirements.txt
- Set DUBSTUDIO_API_TOKEN, OLLAMA_URL, OLLAMA_MODEL, WHISPER_MODEL
- Demucs: pip install demucs; set ENABLE_DEMUCS=1
- VoxCPM2: pip install voxcpm; set ENABLE_VOXCPM=1, VOXCPM_MODEL=openbmb/VoxCPM2
- Optional diarization: pyannote.audio + model access, HF_TOKEN, ENABLE_DIARIZATION=1
- Start: uvicorn main:app --host 0.0.0.0 --port 8000
- Expose server through HTTPS only; never place backend token into source control.

Audio checks:
- Voice synthesis only starts after all Khmer subtitles are translated.
- Every voice sample requires explicit consent (only use your own voice or a licensed/consented recording).
- Audio samples are deleted as jobs expire (default 24 hours, cleanup on next upload).
- Sync begins at subtitle timestamps; if generated speech is longer than its SRT window, an explicit warning is returned. It does NOT automatically guarantee lip sync, matching original gender, or character identity.
- The audio deliverable here is WAV; MP4 dubbing/export is not yet implemented in Mobile v1.1.
