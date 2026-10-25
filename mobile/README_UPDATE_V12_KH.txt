DubStudio KH Pro Mobile v1.2 — Improvements
==========================================
1. Check AI Server: test authenticated HTTPS connection and show configured Faster-Whisper, Demucs, VoxCPM2 and Speaker Scan before uploading video.
2. Smart preflight: avoid uploading a large video when selected Demucs is unavailable.
3. Resume Khmer Translation: keep completed original and Khmer lines, resume only missing lines after a failed translation.
4. Voice Clone still requires explicit permission from voice owner and a per-speaker reference audio.
5. The Android APK is a client. Heavy models run on your own configured cloud backend; no public GPU server is included.

Use: Open Android app → enter your HTTPS Backend URL and API Token → Check AI Server → Choose Video → Generate Khmer Subtitles → (optional) Generate AI Voice.

សូមភ្ជាប់ AI Backend មុន Generate។ APK មិនផ្ទុក AI Models ធំៗនៅក្នុងទូរស័ព្ទទេ។
