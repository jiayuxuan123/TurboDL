"""Public C ABI media tests against generated local audio/video and real HTTP."""
import ctypes as C
import hashlib
import http.server
import json
import os
import shutil
from pathlib import Path
import struct
import subprocess
import tempfile
import threading
import time
import unittest
from test_engine import ABI,COMPLETED,FAILED,PAUSED,CANCELLED,EngineCase,TaskOptions,Policy,ExtendedOptions,Snapshot,Resources

class MediaOptions(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('transfer',C.POINTER(TaskOptions)),('protocol',C.c_uint32),('parallel',C.c_uint32),('bandwidth',C.c_uint64),('max_segment_bytes',C.c_uint64),('audio_language',C.c_char_p),('video_id',C.c_char_p),('audio_id',C.c_char_p),('record_seconds',C.c_uint32),('reserved',C.c_uint32)]
class MediaDetails(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('stage',C.c_uint32),('failure_stage',C.c_uint32),('tracks',C.c_uint32),('live',C.c_uint32),('units',C.c_uint64),('verified',C.c_uint64),('recorded_ms',C.c_uint64)]

def adts_parts(data):
    frames=[];offset=0
    while offset<len(data):
        if data[offset]!=255 or data[offset+1]&0xf6!=0xf0:raise AssertionError('generated ADTS signature invalid')
        size=((data[offset+3]&3)<<11)|(data[offset+4]<<3)|(data[offset+5]>>5)
        if size<7 or offset+size>len(data):raise AssertionError('generated ADTS frame invalid')
        frames.append(data[offset:offset+size]);offset+=size
    return [b''.join(frames[i:i+40]) for i in range(0,len(frames),40)]

def mp4_parts(data):
    offset=0;moofs=[]
    while offset<len(data):
        size,kind=struct.unpack_from('>I4s',data,offset)
        if size==1:size=struct.unpack_from('>Q',data,offset+8)[0]
        if size<8 or offset+size>len(data):raise AssertionError('generated MP4 box invalid')
        if kind==b'moof':moofs.append(offset)
        offset+=size
    if not moofs:raise AssertionError('generated MP4 is not fragmented')
    return data[:moofs[0]],[data[p:(moofs[i+1] if i+1<len(moofs) else len(data))] for i,p in enumerate(moofs)]

class MediaFixture(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def handle(self):
        try:super().handle()
        except (ConnectionError,OSError):pass
    def do_GET(self):
        server=self.server
        with server.lock:server.requests.append((self.path,self.headers.get('Range'),self.headers.get('Authorization')))
        if self.path in server.status:
            self.send_response(server.status[self.path]);self.send_header('Content-Length','0');self.end_headers();return
        if self.path=='/redirect.m3u8':
            self.send_response(302);self.send_header('Location','/nested/plain.m3u8');self.send_header('Content-Length','0');self.end_headers();return
        payload=server.assets.get(self.path)
        if payload is None:
            self.send_response(404);self.send_header('Content-Length','0');self.end_headers();return
        if self.path in ('/live.m3u8','/live-noend.m3u8'):
            with server.lock:server.live_reads+=1;revision=server.live_reads
            start=min(max(0,revision-1),len(server.media_parts)-2);end=start+2
            text='#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:'+str(start)+'\n'
            text+=''.join(f'#EXTINF:0.85,\np{i}.aac\n' for i in range(start,end))
            if self.path=='/live.m3u8' and end==len(server.media_parts):text+='#EXT-X-ENDLIST\n'
            payload=text.encode()
        begin,end=0,len(payload)-1;header=self.headers.get('Range')
        if header:
            a,b=header.removeprefix('bytes=').split('-');begin=int(a);end=min(int(b),end)
        if begin>=len(payload):
            self.send_response(416);self.send_header('Content-Length','0');self.end_headers();return
        body=payload[begin:end+1]
        self.send_response(206 if header else 200);self.send_header('Content-Length',str(len(body)))
        if not server.weak:self.send_header('ETag','"generated-media-v1"')
        if header:self.send_header('Content-Range',f'bytes {begin}-{end}/{len(payload)}')
        self.end_headers()
        try:
            if server.hook:server.hook(self.path)
            gate=server.body_gates.get(self.path)
            chunk=64 if self.path in server.slow or gate else 8192
            offset=0
            while offset<len(body):
                part=body[offset:offset+chunk];self.wfile.write(part);self.wfile.flush();offset+=len(part)
                # Hold a real partial response until the test has observed a
                # verified sibling. Heartbeats avoid triggering low-speed timeouts.
                if gate:
                    while offset<len(body)-1 and not gate.wait(.1):
                        self.wfile.write(body[offset:offset+1]);self.wfile.flush();offset+=1
                if self.path in server.slow:time.sleep(.006)
        except (ConnectionError,OSError):pass

class MediaTests(EngineCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.lib.td_media_submit.argtypes=[C.c_void_p,C.POINTER(MediaOptions),C.POINTER(C.c_uint64)]
        cls.lib.td_media_query.argtypes=[C.c_void_p,C.c_uint64,C.POINTER(MediaDetails)]
        cls.lib.td_media_finish.argtypes=[C.c_void_p,C.c_uint64]
        parent=Path(os.environ['TD_MEDIA_TEST_ROOT']);parent.mkdir(parents=True,exist_ok=True)
        cls.generated=tempfile.TemporaryDirectory(prefix='generated-',dir=parent)
        cls.generated_path=Path(cls.generated.name)
        cls.fixture=os.environ['TD_MEDIA_FIXTURE']
        run=subprocess.run([cls.fixture,'generate',str(cls.generated_path)],capture_output=True,text=True,timeout=30)
        if run.returncode:cls.generated.cleanup();raise AssertionError(run.stdout+run.stderr)
        cls.audio=(cls.generated_path/'audio.aac').read_bytes();cls.audio_parts=adts_parts(cls.audio)
        cls.audio_mp4=(cls.generated_path/'audio.mp4').read_bytes();cls.audio_init,cls.audio_fragments=mp4_parts(cls.audio_mp4)
        cls.video_mp4=(cls.generated_path/'video.mp4').read_bytes();cls.video_init,cls.video_fragments=mp4_parts(cls.video_mp4)
    @classmethod
    def tearDownClass(cls):cls.generated.cleanup()
    def setUp(self):
        parent=Path(os.environ['TD_MEDIA_TEST_ROOT']);self.temp=tempfile.TemporaryDirectory(prefix='media-case-',dir=parent);self.path=Path(self.temp.name);self.engines=[]
        for port in range(8791,8851):
            try:self.server=http.server.ThreadingHTTPServer(('127.0.0.1',port),MediaFixture);break
            except OSError:continue
        else:raise RuntimeError('no media fixture port')
        self.server.daemon_threads=True;self.server.assets={};self.server.requests=[];self.server.lock=threading.Lock();self.server.slow=set();self.server.body_gates={};self.server.weak=False;self.server.status={};self.server.hook=None;self.server.live_reads=0;self.server.media_parts=self.audio_parts
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start();self.url=f'http://127.0.0.1:{self.server.server_port}'
        for i,part in enumerate(self.audio_parts):self.server.assets[f'/p{i}.aac']=part
        plain='#EXTM3U\n#EXT-X-TARGETDURATION:1\n'+''.join(f'#EXTINF:0.85,\np{i}.aac\n' for i in range(len(self.audio_parts)))+'#EXT-X-ENDLIST\n'
        self.server.assets['/plain.m3u8']=plain.encode()
        self.server.assets['/live.m3u8']=b'live';self.server.assets['/live-noend.m3u8']=b'live'
    def tearDown(self):
        for gate in self.server.body_gates.values():gate.set()
        for e in self.engines:self.assertEqual(0,self.lib.td_engine_destroy(e))
        self.server.shutdown();self.server.server_close();self.thread.join(3)
        if os.name=='nt':shutil.rmtree('\\\\?\\'+str(self.path))
        self.temp.cleanup()
    def engine(self,slots=3,connections=8,buffers=16*1024*1024):return super().engine(slots,connections,buffers)
    def media(self,e,path='/plain.m3u8',protocol=1,key='media',record=0,bandwidth=0):
        policy=Policy(C.sizeof(Policy),ABI,2,8,self.path.name.encode())
        transfer=ExtendedOptions();transfer.struct_size=C.sizeof(transfer);transfer.abi_version=ABI;transfer.url=(self.url+path).encode();transfer.destination=str(self.path/(key+'.mp4')).encode();transfer.stable_key=key.encode();transfer.connections=4;transfer.request_window_bytes=262144;transfer.connect_timeout_ms=1500;transfer.read_timeout_ms=4000;transfer.policy=C.pointer(policy)
        options=MediaOptions(C.sizeof(MediaOptions),ABI,C.cast(C.pointer(transfer),C.POINTER(TaskOptions)),protocol,4,bandwidth,67108864,None,None,None,record,0)
        task=C.c_uint64();self.assertEqual(0,self.lib.td_media_submit(e,C.byref(options),C.byref(task)));return task.value
    def details(self,e,task):
        d=MediaDetails();d.struct_size=C.sizeof(d);d.abi_version=ABI;self.assertEqual(0,self.lib.td_media_query(e,task,C.byref(d)));return d
    def inspect(self,path):
        run=subprocess.run([self.fixture,'inspect',str(path)],capture_output=True,text=True,timeout=30)
        self.assertEqual(0,run.returncode,run.stderr);return json.loads(run.stdout)
    def assert_audio(self,path,reference=None):
        result=self.inspect(path);expected=self.inspect(reference or self.generated_path/'audio.aac')
        actual=next(s for s in result['streams'] if s['codec']=='aac');wanted=next(s for s in expected['streams'] if s['codec']=='aac')
        self.assertEqual(wanted['packet_sha256'],actual['packet_sha256']);self.assertEqual(wanted['packets'],actual['packets']);self.assertGreater(actual['samples'],100000)
        self.assertGreater(result['duration_us'],3000000);self.assertLess(result['duration_us'],3500000)
    def test_hls_plain_completes_with_decodable_exact_audio_packets(self):
        e=self.engine();task=self.media(e);done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);output=self.path/'media.mp4';self.assert_audio(output)
        self.assertEqual(hashlib.sha256(output.read_bytes()).hexdigest(),done.sha256.decode());self.assertEqual(output.stat().st_size,done.total_bytes)
        details=self.details(e,task);self.assertEqual(5,details.stage);self.assertEqual(details.units,details.verified)
        with self.server.lock:requests=list(self.server.requests)
        self.assertEqual(1+len(self.audio_parts),len(requests));self.assertTrue(all(r[1] is None for r in requests),'HLS objects must not incur range probes')
    def test_cli_hls_saves_decodable_audio(self):
        name = 'tdl-vnext.exe' if os.name == 'nt' else 'tdl-vnext'
        cli = os.environ.get('TD_PUBLIC_CLI') or str(Path(os.environ['TD_ENGINE_LIBRARY']).with_name(name))
        output = self.path / 'cli-hls.mp4'
        result = subprocess.run(
            [cli, '--hls', self.url + '/plain.m3u8', '-o', str(output), '--segments', '3',
             '--deadline', '15', '--allow-loopback-fixture'],
            capture_output=True, text=True, encoding='utf-8', timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout[-800:])
        self.assert_audio(output)
    def encrypted(self,plain,key,iv):
        from cryptography.hazmat.primitives.ciphers import Cipher,algorithms,modes
        from cryptography.hazmat.primitives.padding import PKCS7
        pad=PKCS7(128).padder();data=pad.update(plain)+pad.finalize();encrypt=Cipher(algorithms.AES(key),modes.CBC(iv)).encryptor()
        return encrypt.update(data)+encrypt.finalize()
    def test_hls_rotating_aes_keys_produce_the_original_audio(self):
        keys=[hashlib.sha256(f'local-test-key-{i}'.encode()).digest()[:16] for i in range(2)]
        self.server.assets['/key0']=keys[0];self.server.assets['/key1']=keys[1]
        text='#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1000\n'
        for i,part in enumerate(self.audio_parts):
            index=0 if i<2 else 1
            if i in (0,2):text+=f'#EXT-X-KEY:METHOD=AES-128,URI="/key{index}"'+(',IV=0x1234' if index else '')+'\n'
            iv=(1000+i if index==0 else 0x1234).to_bytes(16,'big')
            self.server.assets[f'/encrypted-{i}.aac']=self.encrypted(part,keys[index],iv)
            text+=f'#EXTINF:0.85,\nencrypted-{i}.aac\n'
        self.server.assets['/aes.m3u8']=(text+'#EXT-X-ENDLIST\n').encode()
        e=self.engine();task=self.media(e,'/aes.m3u8');done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio(self.path/'media.mp4')
        with self.server.lock:requests=[r[0] for r in self.server.requests]
        self.assertEqual(1,requests.count('/key0'));self.assertEqual(1,requests.count('/key1'))
    def test_hls_byte_ranges_and_initialization_form_a_playable_file(self):
        self.server.assets['/blob.mp4']=self.audio_mp4
        text='#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI="/blob.mp4",BYTERANGE="'+str(len(self.audio_init))+'@0"\n'
        offset=len(self.audio_init)
        for fragment in self.audio_fragments:
            text+=f'#EXTINF:1,\n#EXT-X-BYTERANGE:{len(fragment)}@{offset}\nblob.mp4\n';offset+=len(fragment)
        self.server.assets['/ranges.m3u8']=(text+'#EXT-X-ENDLIST\n').encode()
        e=self.engine();task=self.media(e,'/ranges.m3u8');done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio(self.path/'media.mp4',self.generated_path/'audio.mp4')
        with self.server.lock:ranges=[r[1] for r in self.server.requests if r[0]=='/blob.mp4']
        self.assertEqual(len(self.audio_fragments)+1,len(ranges));self.assertTrue(all(r is not None for r in ranges))
    def install_tracks(self):
        for kind,init,fragments in [('audio',self.audio_init,self.audio_fragments),('video',self.video_init,self.video_fragments)]:
            self.server.assets[f'/{kind}/init.mp4']=init
            for i,fragment in enumerate(fragments,1):self.server.assets[f'/{kind}/part-{i:05}.m4s']=fragment
            playlist='#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI="init.mp4"\n'+''.join(f'#EXTINF:1,\npart-{i:05}.m4s\n' for i in range(1,len(fragments)+1))+'#EXT-X-ENDLIST\n'
            self.server.assets[f'/{kind}/list.m3u8']=playlist.encode()
    def assert_audio_video(self,path):
        result=self.inspect(path);self.assertEqual(2,len(result['streams']))
        for codec,reference in [('aac','audio.mp4'),('mpeg4','video.mp4')]:
            actual=next(s for s in result['streams'] if s['codec']==codec);expected=self.inspect(self.generated_path/reference)['streams'][0]
            self.assertEqual(expected['packet_sha256'],actual['packet_sha256']);self.assertEqual(expected['packets'],actual['packets'])
            self.assertGreater(actual['frames'],20)
    def test_hls_master_selects_and_muxes_audio_video_tracks(self):
        self.install_tracks()
        self.server.assets['/master.m3u8']=b'#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Main",LANGUAGE="zh",DEFAULT=YES,URI="audio/list.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,CODECS="mp4v.20.9,mp4a.40.2",AUDIO="a"\nvideo/list.m3u8\n'
        e=self.engine();task=self.media(e,'/master.m3u8');done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio_video(self.path/'media.mp4')
    def test_dash_templates_download_and_mux_the_chosen_tracks(self):
        self.install_tracks()
        mpd='<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT3.2S"><Period>'
        for kind in ['audio','video']:
            mpd+=f'<AdaptationSet mimeType="{kind}/mp4" contentType="{kind}" lang="zh"><BaseURL>/{kind}/</BaseURL><SegmentTemplate timescale="1000" duration="1000" initialization="init.mp4" media="part-$Number%05d$.m4s" startNumber="1"/><Representation id="{kind}" bandwidth="96000"/></AdaptationSet>'
        self.server.assets['/manifest.mpd']=(mpd+'</Period></MPD>').encode()
        e=self.engine();task=self.media(e,'/manifest.mpd',protocol=2);done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio_video(self.path/'media.mp4')
    def test_redirected_manifest_resolves_relative_segment_paths(self):
        self.server.assets['/nested/plain.m3u8']=self.server.assets['/plain.m3u8']
        for i,part in enumerate(self.audio_parts):self.server.assets[f'/nested/p{i}.aac']=part
        e=self.engine();task=self.media(e,'/redirect.m3u8');done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio(self.path/'media.mp4')
    def test_pause_recreation_reuses_complete_segments_and_restarts_weak_partial(self):
        self.server.weak=True;self.server.slow={'/p0.aac'}
        self.server.body_gates['/p0.aac']=threading.Event()
        e=self.engine(slots=3);task=self.media(e)
        self.until(e,task,lambda s:self.details(e,task).verified>0 and s.active_requests>0,timeout=10)
        self.assertEqual(0,self.lib.td_task_control(e,task,1));paused=self.until(e,task,lambda s:s.state in (PAUSED,FAILED),timeout=10)
        self.assertEqual(PAUSED,paused.state,paused.message);self.assertEqual(0,paused.active_requests)
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        with self.server.lock:before=[r[0] for r in self.server.requests]
        self.server.body_gates['/p0.aac'].set();self.server.slow=set();e=self.engine();task=self.media(e)
        done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio(self.path/'media.mp4')
        with self.server.lock:after=[r[0] for r in self.server.requests]
        self.assertLess(len(after)-len(before),1+len(self.audio_parts),'resume redownloaded every complete segment')
    def test_pause_resume_same_engine_retires_children_and_reuses_verified_units(self):
        self.server.weak=True;self.server.slow={'/p0.aac'}
        self.server.body_gates['/p0.aac']=threading.Event()
        e=self.engine(slots=4,connections=8);task=self.media(e,key='media-same-engine')
        self.until(e,task,lambda s:self.details(e,task).verified>0 and s.active_requests>0,timeout=10)
        self.assertEqual(0,self.lib.td_task_control(e,task,1));paused=self.until(e,task,lambda s:s.state in (PAUSED,FAILED),timeout=10)
        self.assertEqual(PAUSED,paused.state,paused.message);self.assertEqual(0,paused.active_requests)
        with self.server.lock:before=[r[0] for r in self.server.requests]
        self.server.body_gates['/p0.aac'].set();self.server.slow=set();self.assertEqual(0,self.lib.td_task_control(e,task,2))
        resumed=self.until(e,task,lambda s:(s.state in (COMPLETED,FAILED) and s.generation==2) or s.state==FAILED,timeout=20)
        self.assertEqual(COMPLETED,resumed.state,resumed.message);self.assertEqual(2,resumed.generation)
        self.assert_audio(self.path/'media-same-engine.mp4')
        with self.server.lock:after=[r[0] for r in self.server.requests]
        self.assertLess(len(after)-len(before),1+len(self.audio_parts),'same-engine resume redownloaded every complete segment')
    def test_failed_target_commit_only_retries_saving(self):
        output=self.path/'media.mp4';last=f'/p{len(self.audio_parts)-1}.aac'
        def block(path):
            if path==last:output.mkdir(exist_ok=True)
        self.server.hook=block;e=self.engine();task=self.media(e)
        failed=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(FAILED,failed.state,failed.message);self.assertEqual(4,self.details(e,task).failure_stage,failed.message)
        with self.server.lock:before=len(self.server.requests)
        output.rmdir();self.server.hook=None;self.assertEqual(0,self.lib.td_task_control(e,task,2))
        done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED) and s.generation==2,timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio(output)
        with self.server.lock:self.assertEqual(before,len(self.server.requests))
    def test_live_playlist_updates_are_deduplicated_until_endlist(self):
        e=self.engine();task=self.media(e,'/live.m3u8');done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio(self.path/'media.mp4');self.assertEqual(1,self.details(e,task).live)
        with self.server.lock:requests=[r[0] for r in self.server.requests]
        for i in range(len(self.audio_parts)):self.assertEqual(1,requests.count(f'/p{i}.aac'))
    def test_live_recording_can_be_finished_explicitly(self):
        e=self.engine();task=self.media(e,'/live-noend.m3u8')
        self.until(e,task,lambda s:self.details(e,task).verified>=len(self.audio_parts),timeout=12)
        self.assertEqual(0,self.lib.td_media_finish(e,task));done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message);self.assert_audio(self.path/'media.mp4')
    def test_jni_live_recording_reports_progress_and_publishes_playable_output(self):
        java = Path(os.environ['TD_JAVA_HOME']) / 'bin' / 'java.exe'
        run = subprocess.run(
            [str(java), '-cp', os.environ['TD_JAVA_CLASSES'],
             'dev.turbodl.nativebridge.NativeMediaSmoke', os.environ['TD_ENGINE_LIBRARY'],
             str(self.path), self.url + '/live-noend.m3u8', str(len(self.audio_parts))],
            capture_output=True, text=True, timeout=25,
        )
        self.assertEqual(0, run.returncode, run.stdout + run.stderr)
        self.assert_audio(self.path / 'jni-live.mp4')
    def test_key_http_failure_reports_status_and_never_publishes_output(self):
        self.server.status['/denied-key']=403
        self.server.assets['/denied.m3u8']=b'#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-KEY:METHOD=AES-128,URI="/denied-key"\n#EXTINF:1,\np0.aac\n#EXT-X-ENDLIST\n'
        e=self.engine();task=self.media(e,'/denied.m3u8');failed=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=15)
        self.assertEqual(FAILED,failed.state);self.assertEqual(403,failed.http_status);self.assertFalse((self.path/'media.mp4').exists())
    def test_invalid_padding_is_not_published(self):
        key=hashlib.sha256(b'local-test-invalid-padding').digest()[:16];iv=(1).to_bytes(16,'big')
        self.server.assets['/padding-key']=key;cipher=self.encrypted(self.audio_parts[0],key,iv);self.server.assets['/padding.aac']=cipher[:-1]+bytes([cipher[-1]^255])
        self.server.assets['/padding.m3u8']=b'#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-KEY:METHOD=AES-128,URI="/padding-key",IV=0x1\n#EXTINF:1,\npadding.aac\n#EXT-X-ENDLIST\n'
        e=self.engine();task=self.media(e,'/padding.m3u8');failed=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=15)
        self.assertEqual(FAILED,failed.state);self.assertEqual(4,failed.error);self.assertEqual(3,self.details(e,task).failure_stage)
        self.assertFalse((self.path/'media.mp4').exists());self.assertFalse(list(self.path.rglob('*.decrypting')))

if __name__=='__main__':unittest.main(verbosity=2)
