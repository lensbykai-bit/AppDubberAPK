import os
import sys
import time
import unittest
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'cloud'))
from fastapi.testclient import TestClient

os.environ['DUBSTUDIO_ACCESS_TOKEN'] = 'development-only-test-token-123456789'
import app as cloud

TOKEN = os.environ['DUBSTUDIO_ACCESS_TOKEN']
class CloudTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(cloud.app)
        cls.headers = {'Authorization': 'Bearer '+TOKEN}

    def test_health_is_public_and_status_is_private(self):
        self.assertEqual(self.client.get('/health').status_code, 200)
        self.assertEqual(self.client.get('/api/status').status_code, 401)
        self.assertEqual(self.client.get('/api/status', headers=self.headers).status_code, 200)
        self.assertTrue(self.client.get('/api/status', headers=self.headers).json()['job_api'])

    def test_empty_secret_disables_jobs(self):
        with patch.dict(os.environ, {'DUBSTUDIO_ACCESS_TOKEN':''}):
            self.assertEqual(self.client.get('/api/status', headers=self.headers).status_code, 503)

    def test_translation_validation(self):
        request = {'subtitles':[{'start':2.0,'end':1.0,'text':'Hello','voice':'Female'}]}
        self.assertEqual(self.client.post('/api/translate', headers=self.headers, json=request).status_code, 422)
        request['subtitles'][0]['end'] = 3.0
        with patch.object(cloud, 'translate_lines', return_value={'subtitles':request['subtitles']}):
            response = self.client.post('/api/translate', headers=self.headers, json=request)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['subtitles'][0]['start'],2.0)

    def test_job_lifecycle_and_file_download(self):
        def fake_generate(job_id, lines, directory):
            output = directory/'dubstudio-voice.wav'
            output.write_bytes(b'RIFF'+b'00000000')
            with cloud.LOCK:
                cloud.JOBS[job_id]['output'] = str(output)
                cloud.JOBS[job_id]['filename'] = output.name
            return {'fileSize':output.stat().st_size}
        with patch.object(cloud, 'generate_audio', side_effect=fake_generate):
            request={'subtitles':[{'start':0,'end':1.2,'text':'ជំរាបសួរ','voice':'Female'}]}
            response=self.client.post('/api/jobs/audio',headers=self.headers,json=request)
            self.assertEqual(response.status_code,202)
            job_id=response.json()['job_id']
            for _ in range(150):
                result=self.client.get('/api/jobs/'+job_id,headers=self.headers).json()
                if result['status'] in ('done','error'):break
                time.sleep(.01)
            self.assertEqual(result['status'],'done', result)
            self.assertEqual(result['percent'],100)
            response=self.client.get('/api/jobs/'+job_id+'/download',headers=self.headers)
            self.assertEqual(response.status_code,200)
            self.assertTrue(response.content.startswith(b'RIFF'))

    def test_empty_and_missing_media(self):
        response=self.client.post('/api/jobs/transcribe',headers=self.headers,files={'video':('empty.mp4',b'', 'video/mp4')})
        self.assertEqual(response.status_code,422)
        self.assertEqual(self.client.get('/api/jobs/0'*32,headers=self.headers).status_code,404)

if __name__=='__main__':unittest.main()
