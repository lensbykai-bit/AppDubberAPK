import io
from unittest.mock import patch

from test_api import client, AUTH, main


def test_resume_only_untranslated_lines():
    def fake_extract(folder):
        p = folder / 'speech.wav'
        p.write_bytes(b'fake')
        return p
    def fake_transcribe(audio, language):
        return ([
            {'start': 0.0, 'end': 1.0, 'speaker': 'SPEAKER_01', 'original': 'Hello', 'khmer': ''},
            {'start': 1.0, 'end': 2.0, 'speaker': 'SPEAKER_02', 'original': 'Welcome', 'khmer': ''},
        ], 'en')
    calls = []
    def fail_second(text, language):
        calls.append(text)
        if text == 'Welcome':
            raise RuntimeError('Ollama offline')
        return 'សួស្តី'
    with patch.object(main, 'extract_audio', fake_extract), \
         patch.object(main, 'transcribe_audio', fake_transcribe), \
         patch.object(main, 'translate_to_khmer', fail_second):
        r = client.post('/api/jobs', headers=AUTH,
                        files={'video': ('clip.mp4', io.BytesIO(b'video'), 'video/mp4')},
                        data={'scan_speakers': 'false'})
    job = r.json()['job_id']
    assert client.get(f'/api/jobs/{job}', headers=AUTH).json()['stage'] == 'failed'
    assert calls == ['Hello','Welcome']

    resume_calls = []
    def finish_second(text, language):
        resume_calls.append(text)
        return 'ស្វាគមន៍'
    with patch.object(main, 'translate_to_khmer', finish_second):
        reply = client.post(f'/api/jobs/{job}/resume-translation', headers=AUTH)
    assert reply.status_code == 200, reply.text
    assert reply.json()['remaining'] == 1
    assert resume_calls == ['Welcome']
    state = client.get(f'/api/jobs/{job}', headers=AUTH).json()
    assert state['stage'] == 'done' and state['khmer_ready']
    srt = client.get(f'/api/jobs/{job}/srt?kind=km', headers=AUTH).text
    assert 'សួស្តី' in srt and 'ស្វាគមន៍' in srt
    assert client.post(f'/api/jobs/{job}/resume-translation', headers=AUTH).status_code == 409


def test_health_reports_packages_without_loading_models():
    health = client.get('/api/health', headers=AUTH).json()
    for key in ('ok', 'ffmpeg_installed', 'faster_whisper_installed', 'demucs_enabled',
                'demucs_installed', 'voxcpm_enabled', 'voxcpm_installed'):
        assert key in health
