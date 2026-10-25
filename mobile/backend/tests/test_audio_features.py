import io
import json
import os
import tempfile
from pathlib import Path
from unittest.mock import patch
import numpy as np
from fastapi.testclient import TestClient

os.environ['DUBSTUDIO_API_TOKEN'] = 'test-only-secret'
os.environ['DUBSTUDIO_DATA_DIR'] = tempfile.mkdtemp(prefix='dubstudio-v2-tests-')
import main
import voice_pipeline

client = TestClient(main.app)
AUTH = {'Authorization': 'Bearer test-only-secret'}


def job():
    from uuid import uuid4
    jid = uuid4().hex
    folder = main.APP_DIR / jid
    folder.mkdir()
    main.write_json_atomic(folder / 'status.json', {'job_id':jid,'stage':'done','percent':100})
    result = {'subtitles':[{'speaker':'SPEAKER_01','start':0.1,'end':1.1, 'original':'Hello', 'khmer':'សួស្តី'},
                           {'speaker':'SPEAKER_02','start':1.1,'end':2.3, 'original':'Goodbye', 'khmer':'លាហើយ'}], 'speaker_names':{}}
    main.write_json_atomic(folder / 'result.json',result)
    return jid, folder


def test_voice_consent_and_speaker_guards():
    jid, _ = job()
    r = client.post(f'/api/jobs/{jid}/voice-reference',headers=AUTH, data={'speaker':'SPEAKER_01','consent':'false'},files={'audio':('a.wav',io.BytesIO(b'abc'),'audio/wav')})
    assert r.status_code == 400
    r = client.post(f'/api/jobs/{jid}/voice-reference',headers=AUTH,data={'speaker':'INVALID','consent':'true'},files={'audio':('a.wav',io.BytesIO(b'abc'),'audio/wav')})
    assert r.status_code == 400
    r = client.post(f'/api/jobs/{jid}/voice-reference',data={'speaker':'SPEAKER_01','consent':'true'},files={'audio':('a.wav',io.BytesIO(b'abc'),'audio/wav')})
    assert r.status_code == 401


def test_audio_requires_translated_subtitles_and_model_enabled():
    jid, folder = job()
    with patch.object(main,'ENABLE_VOXCPM',False):
        r=client.post(f'/api/jobs/{jid}/generate-voice',headers=AUTH,json={})
        assert r.status_code == 503
    with patch.object(main,'ENABLE_VOXCPM',True):
        payload=main.read_json(folder/'result.json')
        payload['subtitles'][1]['khmer']=''
        main.write_json_atomic(folder/'result.json',payload)
        r=client.post(f'/api/jobs/{jid}/generate-voice',headers=AUTH,json={})
        assert r.status_code == 409


def test_voice_synthesis_mock_and_download():
    jid, folder = job()
    calls=[]
    def fake_speak(text, reference, style):
        calls.append((text, style))
        return (np.sin(np.linspace(0,60,2400)).astype('float32')*0.2, 24000)
    with patch.object(main,'ENABLE_VOXCPM',True),patch.object(voice_pipeline,'_speak',fake_speak):
        r=client.post(f'/api/jobs/{jid}/generate-voice',headers=AUTH,json={'SPEAKER_01':'female','SPEAKER_02':'male'})
        assert r.status_code == 200, r.text
    status=client.get(f'/api/jobs/{jid}',headers=AUTH).json()
    assert status['stage']=='voice_done', status
    assert calls == [('សួស្តី','female'),('លាហើយ','male')]
    output=client.get(f'/api/jobs/{jid}/voice.wav',headers=AUTH)
    assert output.status_code == 200 and output.content[:4]==b'RIFF'


def test_demucs_must_be_available():
    with patch.object(main, 'ENABLE_DEMUCS', False):
        r=client.post('/api/jobs',headers=AUTH,data={'isolate_vocals':'true'},files={'video':('test.mp4',io.BytesIO(b'videofile'),'video/mp4')})
    assert r.status_code == 503


def test_file_reference_upload_real_ffmpeg():
    import shutil, soundfile as sf
    if not shutil.which('ffmpeg'): return
    jid, folder=job()
    t=np.arange(24000,dtype='float32') / 24000
    with io.BytesIO() as stream:
        sf.write(stream, np.sin(2*np.pi*220*t).astype('float32')*0.1, 24000,format='WAV')
        audio=stream.getvalue()
    r=client.post(f'/api/jobs/{jid}/voice-reference',headers=AUTH,data={'speaker':'SPEAKER_01','consent':'true'},files={'audio':('sample.wav',io.BytesIO(audio),'audio/wav')})
    assert r.status_code == 200,r.text
    assert (folder/'voice_references'/'voice_000.wav').exists()
    invalid=client.post(f'/api/jobs/{jid}/voice-reference',headers=AUTH,data={'speaker':'SPEAKER_01','consent':'true'},files={'audio':('bad.wav',io.BytesIO(b'randomdata'),'audio/wav')})
    assert invalid.status_code == 400
