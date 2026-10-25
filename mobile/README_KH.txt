DUBSTUDIO KH PRO MOBILE · ANDROID
=================================
Version 1.0 Starter (Android Studio source + Cloud AI Backend)

មុខងារ៖
  1. Upload Video នៅលើ Android
  2. Scan Character/Speaker ពីសំឡេងក្នុងវីដេអូ (optional diarization)
  3. Generate Subtitle ភាសាដើម (Whisper)
  4. Translate ទៅភាសាខ្មែរ (Ollama Qwen model)
  5. Preview Original + Khmer Subtitle ហើយ Save SRT ទៅទូរស័ព្ទ
  6. Edit Speaker Name ដូចជា SPEAKER_01=ព្រះឯក

រចនាប័ទ្ម៖ Dark Navy + Purple, Rounded Buttons, Simplified Android UI

==== សំខាន់ ====
- ZIP នេះជា Source Code មិនមែន APK ដែលបាន Build រួចទេ។
- Backend ត្រូវតែ Online មុនពេលចុច Generate។ Android Phone មិនគួររត់ Whisper/Ollama 3B ដោយខ្លួនឯង។
- Scan Speaker តាម Audio អាចប្រាប់តែអ្នកនិយាយ A/B/..., មិនប្រាប់ឈ្មោះតួអង្គ ឬភេទដោយប្រាកដទេ។
- បើមិនទាន់កំណត់ Diarization នោះ Speaker ទាំងអស់នឹងជា SPEAKER_01។
- លទ្ធផលត្រូវពិនិត្យជាក់ស្តែង; ភាពត្រឹមត្រូវការបកប្រែអាស្រ័យលើ AI Model។
- Server អាចលុប Job បន្ទាប់ពី 24 ម៉ោង (កំណត់តាម JOB_RETENTION_HOURS)។

==== Android APK ====
Android Studio → Open Folder "android" → Build APK(s).
សូមមើល android/README_BUILD_KH.txt

==== AI BACKEND (សម្រាប់អ្នកមាន Cloud Server) ====
សូមប្រើ Linux VPS/Cloud Server ដែលមាន RAM យ៉ាងហោចណាស់ 8GB សម្រាប់សាកល្បង,
RAM ច្រើនជាងនេះជួយធានាម៉ូដែលធំដំណើរការល្អ។
ដំឡើង Docker និង Docker Compose។

cd backend
cp .env.example .env
កែ DUBSTUDIO_API_TOKEN ក្នុង .env ទៅជា Secret ផ្ទាល់ខ្លួន។
docker compose up -d --build
docker compose exec ollama ollama pull qwen2.5:3b

សាកល្បង API ជាមួយ៖
curl -H "Authorization: Bearer YOUR_SECRET" http://127.0.0.1:8000/api/health

ប្រើ Cloudflare Tunnel / HTTPS Reverse Proxy ដើម្បីផ្តល់ API URL ប្រកបដោយសុវត្ថិភាព។
កុំ Publish Port 8000 ដោយផ្ទាល់ទៅ Internet។ កុំដាក់ API Token ក្នុង Public Repository។
Android app ទទួល HTTPS URL + Secret Token នៅក្នុង UI។

==== SPEAKER SCAN (OPTIONAL) ====
Diarization ត្រូវការបន្ថែម pyannote.audio និង HF_TOKEN
ត្រូវទទួលយកលក្ខខណ្ឌចូលប្រើម៉ូដែល pyannote ជាមុន។
កំណត់ WITH_DIARIZATION=1, ENABLE_DIARIZATION=1 និង HF_TOKEN=... ក្នុង .env
  docker compose up -d --build api
មុខងារនេះត្រូវការធនធានបន្ថែម និងអាចមានកំហុសបែងចែក Speaker។

==== EXPORT ====
- Save Original SRT (ភាសាដើម)
- Save Khmer SRT (មានតែពេលការបកប្រែបានពេញលេញ)
- Video MP4 Dubbing / Voice Generation មិនទាន់រួមក្នុង Android v1.0 នេះទេ។

==== TESTS ====
cd backend
python -m pip install fastapi uvicorn python-multipart requests httpx
python -m unittest discover -s tests -v

No paid AI API key is required to run local Whisper + Ollama, but hosting and computing may cost money.


Version 1.1 new AI services: See README_NEW_FEATURES_KH.txt.
