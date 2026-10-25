import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

# Set env before importing the app; tests do not download models or use Ollama.
os.environ['DUBSTUDIO_API_TOKEN'] = 'test-only-secret'
os.environ['DUBSTUDIO_DATA_DIR'] = tempfile.mkdtemp(prefix='dubstudio-test-')
from fastapi.testclient import TestClient
import main

client = TestClient(main.app)
AUTH = {'Authorization': 'Bearer test-only-secret'}


class TestApi(unittest.TestCase):
    def test_auth(self):
        self.assertEqual(client.get('/api/health').status_code, 401)
        self.assertEqual(client.get('/api/health', headers=AUTH).status_code, 200)

    def test_srt_stamp(self):
        self.assertEqual(main.srt_stamp(82.409), '00:01:22,409')

    def test_not_fake_khmer(self):
        entries = [{'start': 1.0, 'end': 2.5, 'speaker': 'SPEAKER_01', 'original': 'Hello', 'khmer': ''}]
        self.assertEqual(main.make_srt(entries, 'km'), '')
        self.assertIn('Hello', main.make_srt(entries, 'original'))

    def test_workflow_and_speaker_rename(self):
        def fake_extract(folder):
            path = folder / 'speech.wav'
            path.write_bytes(b'fake')
            return path
        def fake_transcribe(audio, language):
            return ([{'start': 0.5, 'end': 1.5, 'speaker': 'SPEAKER_01',
                      'original': 'Good morning', 'khmer': ''}], 'en')
        with patch.object(main, 'extract_audio', fake_extract), \
             patch.object(main, 'transcribe_audio', fake_transcribe), \
             patch.object(main, 'translate_to_khmer', lambda text, lang: 'អរុណសួស្តី'):
            res = client.post('/api/jobs', headers=AUTH,
                              files={'video': ('clip.mp4', io.BytesIO(b'video'), 'video/mp4')},
                              data={'source_language': 'auto', 'scan_speakers': 'false'})
            self.assertEqual(res.status_code, 200, res.text)
            job_id = res.json()['job_id']
        status = client.get(f'/api/jobs/{job_id}', headers=AUTH).json()
        self.assertEqual(status['stage'], 'done')
        srt = client.get(f'/api/jobs/{job_id}/srt?kind=km', headers=AUTH).text
        self.assertIn('អរុណសួស្តី', srt)
        response = client.post(f'/api/jobs/{job_id}/speakers', headers=AUTH, json={'SPEAKER_01':'នាងឯក'})
        self.assertEqual(response.status_code, 200, response.text)
        self.assertIn('នាងឯក', client.get(f'/api/jobs/{job_id}/srt?kind=km', headers=AUTH).text)

    def test_failed_translation_retains_original(self):
        def fake_extract(folder):
            path = folder / 'speech.wav'; path.write_bytes(b'fake'); return path
        def fake_transcribe(audio, language):
            return ([{'start': 0, 'end': 1, 'speaker': 'SPEAKER_01',
                      'original': 'Original phrase', 'khmer': ''}], 'en')
        with patch.object(main, 'extract_audio', fake_extract), \
             patch.object(main, 'transcribe_audio', fake_transcribe), \
             patch.object(main, 'translate_to_khmer', side_effect=RuntimeError('Ollama unavailable')):
            res = client.post('/api/jobs', headers=AUTH,
                              files={'video': ('clip.mp4', io.BytesIO(b'clip'), 'video/mp4')},
                              data={'scan_speakers': 'false'})
            jid = res.json()['job_id']
        s = client.get(f'/api/jobs/{jid}', headers=AUTH).json()
        self.assertEqual(s['stage'], 'failed')
        self.assertTrue(s['original_ready'])
        self.assertFalse(s['khmer_ready'])
        self.assertIn('Original phrase', client.get(f'/api/jobs/{jid}/srt?kind=original', headers=AUTH).text)
        self.assertEqual(client.get(f'/api/jobs/{jid}/srt?kind=km', headers=AUTH).status_code, 409)


if __name__ == '__main__':
    unittest.main()
