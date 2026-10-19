# DubStudio KH Pro Mobile — Cloud Beta v1.1

Android 8.0+ dubbing studio that connects **only to your own authenticated HTTPS Cloud backend**. No Windows computer is needed at runtime. GitHub Actions builds a debug APK in the cloud. No provider API key is embedded in the APK or source.

## Khmer — ដាក់ឱ្យដំណើរការដោយប្រើតែទូរស័ព្ទ

**សំខាន់៖** Source នេះមិនមែនជា Server ដែលបាន Deploy រួចស្រាប់ទេ។ អ្នកត្រូវការ Cloud account និង OpenAI API Key ដែលមានសិទ្ធិប្រើប្រាស់/គិតថ្លៃតាមការប្រើប្រាស់។ **កុំផ្ញើ API Key មកក្នុង Chat ឬដាក់វាក្នុង GitHub**។

1. ប្រើ Chrome លើ Android។ បើក `https://render.com/deploy?repo=https://github.com/lensbykai-bit/AppDubberAPK/tree/dubstudio-kh-pro-cloud` ឬ Render Dashboard → **New → Blueprint** → ជ្រើស repo `lensbykai-bit/AppDubberAPK`, branch `dubstudio-kh-pro-cloud`។
2. នៅពេលស្នើឱ្យបំពេញ Environment Variables ដាក់៖
   - `OPENAI_API_KEY`: API Key ពី OpenAI API Dashboard (រក្សាទុកតែនៅ Cloud)។
   - `DUBSTUDIO_ACCESS_TOKEN`: បង្កើត Password/Token ផ្ទាល់ខ្លួន **យ៉ាងតិច 16 តួអក្សរ** (ណែនាំ 32+ តួអក្សរ random)។ រក្សាវាក្នុង Password Manager ដើម្បីយកទៅដាក់ក្នុង Android App។ វា **មិនមែន**ជា OpenAI API Key ទេ។
3. ចុច **Deploy Blueprint**។ រង់ចាំ status `Live` នៅ Render។ Copy HTTPS URL ដែល Render ផ្ដល់ឱ្យ (ឧ. `https://your-service.onrender.com` កុំប្រើឧទាហរណ៍នេះជាពិត)។
4. ដំឡើង Android APK; បើក App → **Setting** → បញ្ចូល HTTPS URL និង `DUBSTUDIO_ACCESS_TOKEN` ដូចក្នុង Render → **Save** → **Test Connection**។ ចាំឱ្យ AI/FFmpeg/TTS ពី Server បង្ហាញថា Ready។
5. Import MP4/SRT → Generate timed subtitles → Translate Khmer → ជ្រើស Male/Female តាមបន្ទាត់ → Generate WAV → Export MP4។ WAV, SRT និង MP4 រក្សាទុកនៅ `Downloads/DubStudioKHPro` (Android 10+)។

បើ Render Free Sleep ការតភ្ជាប់ដំបូងអាចចំណាយពេលរង់ចាំ។ សាកល្បងវីដេអូខ្លី 10–30 វិនាទីជាមុនសិន។ វីដេអូវែង/ច្រើនបន្ទាត់អាចហួសអង្គចងចាំ ឬកម្រិត Cloud Free។

## Components

- `cloud/app.py`: FastAPI + authenticated job API, bounded uploads, Whisper transcription, OpenAI Khmer translation & recap, Edge Khmer neural TTS, synchronized WAV, FFmpeg video export.
- `Dockerfile`, `render.yaml`: cloud deployment, FFmpeg, env secret prompts. HTTPS handled by Render.
- `app/`: native Android WebView bridge, cloud URL + private client token stored using Android Keystore AES-GCM, Android Storage Access Framework and Downloads.
- `.github/workflows/build-apk.yml`: on-push debug APK build; no local computer needed.
- `tests/test_cloud.py`: authentication, configuration, job and API validation tests.

### Android APK from GitHub

Open `https://github.com/lensbykai-bit/AppDubberAPK/actions` → **Build DubStudio KH Pro Android APK** → successful run on `dubstudio-kh-pro-cloud` branch → Artifacts → download **DubStudio-KH-Pro-Cloud-Mobile-APK**, extract `app-debug.apk` and install. GitHub login may be required for artifacts. The APK is a **debug beta**, not a signed Play Store release.

## HTTP API (Bearer token required)

- `GET /health` — public liveness only.
- `GET /api/status` — verifies token/config.
- `POST /api/translate`, `/api/recap` — 1–100 lines per call.
- `POST /api/jobs/transcribe` — multipart video; accepts up to 200 MB; OpenAI speech recognition.
- `POST /api/jobs/audio` — JSON `subtitles`, same as translation; 1–100 lines.
- `POST /api/jobs/export` — multipart `video` and `audio` fields.
- `GET /api/jobs/{job_id}` — `queued/running/done/error`, real progress for line-by-line TTS, no fake percent for transcription/FFmpeg.
- `GET /api/jobs/{job_id}/download` — authenticated WAV/MP4. Job files are ephemeral and expire about an hour after completion.

**Security**: all non-health endpoints require `Authorization: Bearer <DUBSTUDIO_ACCESS_TOKEN>`. Use HTTPS only. Server environment variables must be set in hosting dashboard, never committed. User videos/audio are sent to the Cloud for processing; extracted speech/translation may go to the external AI provider, and voice text to speech provider. Keep personally sensitive footage private and obtain appropriate permission to upload.

**Operational limits:** max video length 1 hour, up to 200 MB per input, max 100 subtitle lines per audio operation. Translate batches can be 40 lines; a clip with >100 lines needs to be split for audio. Deployment uses one Render worker; background jobs do **not** survive Cloud restarts or Free sleep; files are not durable. Free plan is unsuitable for long/heavy clips. Cloud services/OpenAI APIs may require payment and are **not** included with this source. Edge Khmer speech generation may fail if the provider blocks hosting IP; service availability was not end-to-end tested.

**Not implemented**: automatic gender recognition, VoxCPM model inference, music preservation, burned-in captions, video preview rendering within the APK, persistent cloud job queues, release signing.

## Local automated validation

```
python -m pip install -r cloud/requirements.txt
python -m unittest discover -s tests -v
node --check app/src/main/assets/app.js
```

The tests mock AI/TTS output and do not verify provider or Render connectivity. Use a short clip for live acceptance testing after deployment.
