# DubStudio KH Pro Mobile — Android beta

A real Android WebView application with a self-contained Khmer-friendly mobile UI and native Android storage/network bridge. Includes video/SRT picking, SRT editing, subtitle time adjustment, manual male/female speaker assignment, SRT export, backend transcription/translation/recap, Khmer dubbing WAV generation, and dubbed MP4 export.

**Status:** source is prepared; APK building on Android SDK/Gradle and live AI/Edge TTS have **not** been verified in this environment. This is a beta, not a completed production app. No Google API key or OpenAI key is embedded.

## Build the Android APK

### Option A — Android Studio
1. Extract the ZIP and open the **DubStudio-KH-Pro-Mobile** folder in Android Studio.
2. Install Android SDK 35; use JDK 17 (Android Studio's embedded JDK works).
3. Let Gradle sync. Build > Build APK(s) or run `gradle :app:assembleDebug` (Gradle 8.9).
4. APK appears at `app/build/outputs/apk/debug/app-debug.apk`. Install on an Android 8.0+ phone.

### Option B — GitHub Actions (no Android Studio needed)
1. Create a repository containing all the files in this ZIP, including `.github/workflows/build-apk.yml`.
2. Push to the `main` branch.
3. Open GitHub > Actions > **Build DubStudio KH Pro Android APK** > the successful workflow run > Artifacts.
4. Download `DubStudio-KH-Pro-Mobile-debug-APK.zip`, extract `app-debug.apk`, and install.

An APK is **not** inside the project ZIP. GitHub/Android Studio needs to compile it.

## Connect to an existing backend

The mobile UI connects to the API routes in your existing [DubAI](https://github.com/lensbykai-bit/DubAI) backend. On a PC/server:

```
git clone https://github.com/lensbykai-bit/DubAI.git
cd DubAI
python -m venv .venv
# Windows: .venv\Scripts\activate
# Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
# Set DUBAI_API_KEY (or OPENAI_API_KEY) in your computer's environment securely
uvicorn server:app --host 0.0.0.0 --port 3000
```

FFmpeg/FFprobe must be available to the backend. Both devices need to be on the same *trusted* Wi-Fi network, unless you use a properly authenticated HTTPS deployment. Find the PC's LAN IPv4 address (`ipconfig` on Windows) and enter `http://192.168.x.x:3000` in **Setting** on Android. Test Connection. Do **not** use `localhost` if the backend is on a separate computer. Keep the development API off the public Internet: the upstream DubAI backend has no application authentication.

**Pipeline:** import MP4 → Generate Subtitle (Whisper server) **or** Import SRT → Translate → assign Male/Female (per row) → Generate Dubbing Audio (Edge TTS Khmer) → Export MP4 (replaces video audio). SRT can be saved separately; burned-in captions, BGM mixing, automatic gender detection, true per-request inference %, and VoxCPM inference are **not implemented**. Upload/download byte progress uses real byte counts when available; AI/TTS stages show elapsed time (no fake percent).

Limits inherited from the backend: up to 200 MB uploads; each translation, audio, or recap API call accepts up to 100 subtitle lines. Mobile translation is chunked in batches of 40; dubbing a full clip still needs 100 lines or fewer. VoxCPM is not running natively on the phone; it would need a compatible server-side API added later.

### Privacy and safety
- Never insert API keys into the APK or expose the backend publicly without authentication/TLS.
- Transcription uploads video to the backend and audio may be forwarded to the AI speech transcription provider; translations go to the AI API; TTS text may go to the external speech provider.
- An Android debug APK is for testing. Distribute through a properly signed release build after production QA.
- Voice preview uses whatever Khmer TTS engine is installed on the phone; the exported voices come from the backend and may be unavailable if its external TTS provider rejects requests.
