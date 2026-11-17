"""Black-box C ABI tests. Only local fixtures; never public throughput evidence."""
import ctypes as C
import hashlib
import http.server
import json
import os
from pathlib import Path
import tempfile
import subprocess
import sys
import threading
import time
import unittest

ABI=1
QUEUED,PROBING,TRANSFERRING,PAUSING,PAUSED,VERIFYING,COMPLETED,FAILED,CANCELLED=range(9)
DATA=hashlib.shake_256(b'TurboDL-independent-fixture-20261116').digest(2*1024*1024+73)

class Options(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('checkpoint_directory',C.c_char_p),('max_active_tasks',C.c_uint32),('max_total_connections',C.c_uint32),('buffer_budget_bytes',C.c_uint64),('flags',C.c_uint32),('reserved',C.c_uint32)]
class Header(C.Structure):
    _fields_=[('name',C.c_char_p),('value',C.c_char_p)]
class TaskOptions(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('url',C.c_char_p),('destination',C.c_char_p),('stable_key',C.c_char_p),('expected_sha256',C.c_char_p),('headers',C.POINTER(Header)),('header_count',C.c_uint32),('connections',C.c_uint32),('request_window_bytes',C.c_uint64),('connect_timeout_ms',C.c_uint32),('read_timeout_ms',C.c_uint32)]
class NetworkOptions(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32)]+[(name,C.c_char_p) for name in ['username','password','proxy','ca_bundle','ssh_known_hosts','ssh_public_key','ssh_private_key','doh_url']]+[('http_version',C.c_uint32),('reserved',C.c_uint32)]
class Policy(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('retry_count',C.c_uint32),('max_connections_per_host',C.c_uint32),('cooldown_group',C.c_char_p)]
class ExtendedOptions(C.Structure):
    _fields_=TaskOptions._fields_+[('network',C.POINTER(NetworkOptions)),('policy',C.POINTER(Policy))]
class RequestSlice(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('offset',C.c_uint64),('length',C.c_uint64)]
class ProjectedOptions(C.Structure):
    _fields_=ExtendedOptions._fields_+[('slice',C.POINTER(RequestSlice)),('maximum_response_bytes',C.c_uint64),('representation_id',C.c_char_p)]
class SourceOptions(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('url',C.c_char_p),('headers',C.POINTER(Header)),('header_count',C.c_uint32),('reserved',C.c_uint32)]
class Snapshot(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('task_id',C.c_uint64),('generation',C.c_uint64),('state',C.c_uint32),('error',C.c_uint32),('downloaded_bytes',C.c_uint64),('durable_bytes',C.c_uint64),('wire_bytes',C.c_uint64),('total_bytes',C.c_int64),('request_count',C.c_uint64),('active_requests',C.c_uint32),('peak_active_requests',C.c_uint32),('http_status',C.c_uint32),('reserved',C.c_uint32),('sha256',C.c_char*65),('message',C.c_char*192)]
class Event(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('sequence',C.c_uint64),('kind',C.c_uint32),('reserved',C.c_uint32),('snapshot',Snapshot)]
class Resources(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32)]+[(name,C.c_uint64) for name in ['tasks','connections','buffers','external_transfers','queued_tasks']]
class HttpMetrics(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32)]+[(name,C.c_uint64) for name in ['finished_requests','new_connections','dns_us','connect_us','tls_us','request_first_byte_us','task_first_byte_us']]+[(name,C.c_uint32) for name in ['versions','active_streams','active_connections','peak_connections']]

def load():
    lib=C.CDLL(os.environ['TD_ENGINE_LIBRARY'])
    lib.td_engine_create.argtypes=[C.POINTER(Options),C.POINTER(C.c_void_p)]
    lib.td_engine_destroy.argtypes=[C.c_void_p]
    lib.td_engine_set_speed_limit.argtypes=[C.c_void_p,C.c_uint64]
    lib.td_task_submit.argtypes=[C.c_void_p,C.POINTER(TaskOptions),C.POINTER(C.c_uint64)]
    lib.td_task_query.argtypes=[C.c_void_p,C.c_uint64,C.POINTER(Snapshot)]
    lib.td_task_control.argtypes=[C.c_void_p,C.c_uint64,C.c_uint32]
    lib.td_task_forget.argtypes=[C.c_void_p,C.c_uint64]
    lib.td_task_replace_source.argtypes=[C.c_void_p,C.c_uint64,C.POINTER(SourceOptions)]
    lib.td_engine_poll.argtypes=[C.c_void_p,C.POINTER(Event),C.c_uint32,C.POINTER(C.c_uint32)]
    lib.td_engine_capabilities.restype=C.c_char_p
    lib.td_engine_resources.argtypes=[C.POINTER(Resources)]
    lib.td_task_http_metrics.argtypes=[C.c_void_p,C.c_uint64,C.POINTER(HttpMetrics)]
    return lib

class Fixture(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    requests=[]
    lock=threading.Lock()
    active=0
    peak=0
    short_reads=0
    def log_message(self,*args):pass
    def handle(self):
        try:super().handle()
        except (ConnectionResetError,ConnectionAbortedError,BrokenPipeError):pass
    def do_GET(self):
        with self.lock:
            type(self).active+=1;type(self).peak=max(type(self).peak,type(self).active)
        try:self.serve_get()
        finally:
            with self.lock:type(self).active-=1
    def serve_get(self):
        with self.lock:self.requests.append((self.path,self.headers.get('Range'),self.headers.get('If-Range'),self.headers.get('Authorization')))
        if self.path=='/empty':
            self.send_response(416);self.send_header('Content-Range','bytes */0');self.send_header('Content-Length','5');self.end_headers()
            try:self.wfile.write(b'empty')
            except (ConnectionError,OSError):pass
            return
        if self.path in ('/chunked','/chunked-empty'):
            self.send_response(200);self.send_header('Transfer-Encoding','chunked');self.end_headers()
            payload=DATA if self.path=='/chunked' else b''
            try:
                for i in range(0,len(payload),8192):
                    part=payload[i:i+8192];self.wfile.write(f'{len(part):x}\r\n'.encode()+part+b'\r\n')
                self.wfile.write(b'0\r\n\r\n');self.wfile.flush()
            except (ConnectionError,OSError):pass
            return
        if self.path=='/stall':
            time.sleep(3)
        if self.path=='/limited':
            self.send_response(429);self.send_header('Content-Length','0');self.send_header('Retry-After','300');self.end_headers();return
        if self.path=='/limited-once':
            with self.lock:first=sum(1 for row in self.requests if row[0]==self.path)==1
            if first:
                self.send_response(429);self.send_header('Content-Length','0');self.end_headers();return
        if self.path=='/expired':
            self.send_response(403);self.send_header('Content-Length','0');self.end_headers();return
        if self.path in ('/redirect','/redirect-loop'):
            self.send_response(302);self.send_header('Content-Length','0');self.send_header('Location','/file');self.end_headers();return
        payload=DATA
        etag='"fixture-v1"'
        if self.path=='/changed':payload=bytes(b^0x5a for b in DATA);etag='"fixture-v2"'
        request_range=self.headers.get('Range')
        start,end=0,len(payload)-1
        if request_range:
            raw=request_range.removeprefix('bytes=');a,b=raw.split('-');start=int(a);end=min(int(b) if b else end,end)
        ranged=bool(request_range) and self.path!='/ignore-range'
        if not ranged:start=0;end=len(payload)-1
        if self.path=='/bad-range' and ranged:start+=1
        if start>=len(payload):
            self.send_response(416);self.send_header('Content-Length','0');self.end_headers();return
        body=payload[start:end+1]
        self.send_response(206 if ranged else 200)
        self.send_header('Content-Length',str(len(body)))
        self.send_header('Accept-Ranges','bytes')
        if self.path!='/no-validator':self.send_header('ETag',etag)
        if ranged:self.send_header('Content-Range',f'bytes {start}-{end}/{len(payload)}')
        self.end_headers()
        try:
            if self.path=='/short-once':
                with self.lock:
                    short=type(self).short_reads==0
                    type(self).short_reads+=1
                if short:
                    self.wfile.write(body[:32768]);self.wfile.flush();self.close_connection=True;return
            for i in range(0,len(body),8192):
                self.wfile.write(body[i:i+8192]);self.wfile.flush()
                if self.path in ('/slow','/changed','/no-validator','/sparse','/short-once'):
                    time.sleep(.012 if self.path=='/sparse' and start==0 else .004)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass

class EngineCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lib=load()
        cls.next_port=8791
    def setUp(self):
        while True:
            if self.next_port>8850:raise RuntimeError('local fixture port range exhausted')
            port=type(self).next_port;type(self).next_port+=1
            try:self.server=http.server.ThreadingHTTPServer(('127.0.0.1',port),Fixture);break
            except OSError:continue
        self.server.daemon_threads=True
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.url=f'http://127.0.0.1:{self.server.server_port}'
        self.temp=tempfile.TemporaryDirectory(prefix='turbodl-native-')
        self.path=Path(self.temp.name);self.engines=[]
        with Fixture.lock:Fixture.requests.clear();Fixture.peak=Fixture.active;Fixture.short_reads=0
    def tearDown(self):
        for handle in self.engines:self.assertEqual(0,self.lib.td_engine_destroy(handle))
        self.server.shutdown();self.server.server_close();self.thread.join(2)
        self.temp.cleanup()
    def engine(self,slots=2,connections=4,buffers=1024*1024):
        opts=Options(C.sizeof(Options),ABI,os.fsencode(self.path/'journal'),slots,connections,buffers,1,0)
        handle=C.c_void_p();self.assertEqual(0,self.lib.td_engine_create(C.byref(opts),C.byref(handle)));self.engines.append(handle);return handle
    def submit(self,e,path='/file',key='task',sha=True,connections=1):
        o=TaskOptions(C.sizeof(TaskOptions),ABI,(self.url+path).encode(),str(self.path/(key+'.bin')).encode(),key.encode(),hashlib.sha256(DATA).hexdigest().encode() if sha else None,None,0,connections,256*1024,1000,1500)
        # Independent fixture services can reuse a port after teardown. Their
        # caller scopes must not inherit another test's intentional 5-min wait.
        if path.startswith('/limited'):
            policy=Policy(C.sizeof(Policy),ABI,3,512,self.path.name.encode())
            extended=ExtendedOptions();C.memmove(C.byref(extended),C.byref(o),C.sizeof(o));extended.struct_size=C.sizeof(extended);extended.policy=C.pointer(policy)
            tid=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.cast(C.byref(extended),C.POINTER(TaskOptions)),C.byref(tid)));return tid.value
        tid=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.byref(o),C.byref(tid)));return tid.value
    def query(self,e,t):
        s=Snapshot();s.struct_size=C.sizeof(s);s.abi_version=ABI
        self.assertEqual(0,self.lib.td_task_query(e,t,C.byref(s)));return s
    def resources(self):
        s=Resources();s.struct_size=C.sizeof(s);s.abi_version=ABI
        self.assertEqual(0,self.lib.td_engine_resources(C.byref(s)));return s
    def until(self,e,t,predicate,timeout=8):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            s=self.query(e,t)
            if predicate(s):return s
            time.sleep(.005)
        self.fail(f'task did not settle: state={s.state} error={s.error} message={s.message!r}')

class NativeEngineTests(EngineCase):
    def test_empty_range_representation_can_complete_with_independent_digest(self):
        e=self.engine()
        options=TaskOptions(C.sizeof(TaskOptions),ABI,(self.url+'/empty').encode(),str(self.path/'empty.bin').encode(),b'empty',hashlib.sha256(b'').hexdigest().encode(),None,0,1,262144,1000,1500)
        task=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.byref(options),C.byref(task)))
        done=self.until(e,task.value,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(0,done.total_bytes)
        self.assertEqual(b'',(self.path/'empty.bin').read_bytes())
    def test_chunked_empty_file_finishes_without_missing_progress_error(self):
        e=self.engine()
        options=TaskOptions(C.sizeof(TaskOptions),ABI,(self.url+'/chunked-empty').encode(),str(self.path/'empty.bin').encode(),b'empty',hashlib.sha256(b'').hexdigest().encode(),None,0,1,262144,1000,1500)
        task=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.byref(options),C.byref(task)))
        done=self.until(e,task.value,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(0,done.total_bytes)
        self.assertEqual(b'',(self.path/'empty.bin').read_bytes())
    def test_unknown_length_transfer_publishes_the_final_size(self):
        e=self.engine();t=self.submit(e,'/chunked',key='chunked')
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(len(DATA),done.total_bytes)
        self.assertEqual(DATA,(self.path/'chunked.bin').read_bytes())
    def test_empty_response_after_partial_download_does_not_discard_saved_data(self):
        e=self.engine();t=self.submit(e,'/slow');self.until(e,t,lambda s:s.downloaded_bytes>=131072)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));paused=self.until(e,t,lambda s:s.state==PAUSED)
        source=SourceOptions(C.sizeof(SourceOptions),ABI,(self.url+'/empty').encode(),None,0,0)
        self.assertEqual(0,self.lib.td_task_replace_source(e,t,C.byref(source)));self.assertEqual(0,self.lib.td_task_control(e,t,2))
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED) and s.generation==2)
        self.assertEqual(FAILED,failed.state);self.assertEqual(5,failed.error)
        self.assertGreaterEqual(failed.durable_bytes,paused.durable_bytes)
        with (self.path/'task.bin').open('rb') as f:self.assertEqual(DATA[:paused.durable_bytes],f.read(paused.durable_bytes))
    def test_submission_and_shutdown_wake_an_idle_reactor(self):
        e=self.engine();time.sleep(.1)
        started=time.monotonic();t=self.submit(e,'/slow')
        self.until(e,t,lambda s:s.downloaded_bytes>=8192,timeout=.5)
        self.assertLess(time.monotonic()-started,.5)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));self.until(e,t,lambda s:s.state==PAUSED)
        time.sleep(.1);started=time.monotonic()
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        self.assertLess(time.monotonic()-started,.5)
    def test_waiting_client_is_not_overtaken_and_cancelled_waiter_cannot_block_queue(self):
        a=self.engine(slots=1,connections=4);b=self.engine(slots=1,connections=4)
        first=self.submit(a,'/slow',key='fair-first')
        self.until(a,first,lambda s:s.downloaded_bytes>=131072)
        waiting=self.submit(b,'/slow',key='fair-waiting')
        deadline=time.monotonic()+3
        while self.resources().queued_tasks<1 and time.monotonic()<deadline:time.sleep(.005)
        self.assertGreaterEqual(self.resources().queued_tasks,1)
        later=self.submit(a,'/slow',key='fair-later')
        self.assertEqual(0,self.lib.td_task_control(a,first,1));self.until(a,first,lambda s:s.state==PAUSED)
        self.until(b,waiting,lambda s:s.downloaded_bytes>=131072)
        self.assertEqual(QUEUED,self.query(a,later).state)
        self.assertEqual(0,self.lib.td_task_control(a,later,3));self.until(a,later,lambda s:s.state==CANCELLED)
        self.assertEqual(0,self.lib.td_task_control(a,first,2))
        done=self.until(b,waiting,lambda s:s.state in (COMPLETED,FAILED));self.assertEqual(COMPLETED,done.state,done.message)
        done=self.until(a,first,lambda s:s.state in (COMPLETED,FAILED));self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(0,self.resources().queued_tasks)
    def test_task_slots_are_shared_by_separate_engine_handles(self):
        a=self.engine(slots=1,connections=4);b=self.engine(slots=1,connections=4)
        first=self.submit(a,'/slow',key='engine-one')
        self.until(a,first,lambda s:s.downloaded_bytes>=131072)
        second=self.submit(b,'/slow',key='engine-two')
        self.assertEqual(1,self.resources().tasks)
        self.assertEqual(QUEUED,self.query(b,second).state)
        self.assertEqual(0,self.lib.td_task_control(a,first,1))
        self.until(a,first,lambda s:s.state==PAUSED)
        done=self.until(b,second,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'engine-two.bin').read_bytes())
        self.assertEqual(0,self.resources().tasks)

    def test_capability_report_distinguishes_built_features_from_acceptance(self):
        report=json.loads(self.lib.td_engine_capabilities())
        self.assertTrue(report['experimental']);self.assertFalse(report['parity_verified'])
        self.assertTrue(report['http']);self.assertIn('curl_version',report)
        self.assertIn('http2_compiled',report);self.assertIn('http3_compiled',report)
    def test_single_stream_outputs_independent_sha256(self):
        e=self.engine();t=self.submit(e)
        s=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,s.state,s.message)
        self.assertEqual(hashlib.sha256(DATA).hexdigest(),hashlib.sha256((self.path/'task.bin').read_bytes()).hexdigest())
        self.assertEqual(len(DATA),s.durable_bytes)
        self.assertEqual(hashlib.sha256(DATA).hexdigest(),s.sha256.decode())

    def test_pause_acknowledges_durable_progress_and_resume_uses_range(self):
        e=self.engine();t=self.submit(e,'/slow')
        self.until(e,t,lambda s:s.downloaded_bytes>=131072)
        self.assertEqual(0,self.lib.td_task_control(e,t,1))
        paused=self.until(e,t,lambda s:s.state==PAUSED)
        self.assertGreater(paused.durable_bytes,0)
        self.assertEqual(paused.downloaded_bytes,paused.durable_bytes)
        self.assertEqual(0,paused.active_requests)
        self.assertEqual(0,self.lib.td_task_control(e,t,2))
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(2,done.generation)
        with Fixture.lock:requests=list(Fixture.requests)
        self.assertTrue(any(r[1] and r[1].startswith(f'bytes={paused.durable_bytes}-') and r[2]=='"fixture-v1"' for r in requests))
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_recreating_engine_preserves_saved_download(self):
        e=self.engine();t=self.submit(e,'/slow')
        self.until(e,t,lambda s:s.downloaded_bytes>=262144)
        self.assertEqual(0,self.lib.td_task_control(e,t,1))
        paused=self.until(e,t,lambda s:s.state==PAUSED)
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        e=self.engine();t=self.submit(e,'/slow')
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertLess(done.wire_bytes,len(DATA))
        self.assertEqual(len(DATA)-paused.durable_bytes,done.wire_bytes)
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_changed_etag_keeps_previous_bytes_and_fails(self):
        e=self.engine();t=self.submit(e,'/slow',sha=False)
        self.until(e,t,lambda s:s.downloaded_bytes>=131072)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));self.until(e,t,lambda s:s.state==PAUSED)
        prefix=(self.path/'task.bin').read_bytes()
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        e=self.engine();t=self.submit(e,'/changed',sha=False)
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,failed.state);self.assertEqual(5,failed.error)
        self.assertEqual(prefix,(self.path/'task.bin').read_bytes())

    def test_unverifiable_resume_does_not_append(self):
        e=self.engine();t=self.submit(e,'/no-validator',sha=False)
        self.until(e,t,lambda s:s.downloaded_bytes>=131072)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));self.until(e,t,lambda s:s.state==PAUSED)
        prefix=(self.path/'task.bin').read_bytes()
        self.assertEqual(0,self.lib.td_task_control(e,t,2))
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,failed.state);self.assertEqual(6,failed.error)
        self.assertEqual(prefix,(self.path/'task.bin').read_bytes())

    def test_invalid_content_range_never_marks_complete(self):
        e=self.engine();t=self.submit(e,'/bad-range')
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,failed.state);self.assertEqual(7,failed.error)
        self.assertEqual(0,failed.durable_bytes)

    def test_server_without_range_can_finish_whole_file(self):
        e=self.engine();t=self.submit(e,'/ignore-range')
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_cancel_stalled_response_releases_task_slot(self):
        e=self.engine(slots=1,connections=1);t=self.submit(e,'/stall',key='stalled')
        self.until(e,t,lambda s:s.active_requests==1)
        next_task=self.submit(e,key='next')
        started=time.monotonic();self.assertEqual(0,self.lib.td_task_control(e,t,3))
        cancelled=self.until(e,t,lambda s:s.state==CANCELLED,timeout=1)
        self.assertLess(time.monotonic()-started,1)
        self.assertEqual(0,cancelled.active_requests)
        done=self.until(e,next_task,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)

    def test_rate_limit_keeps_real_status_and_stops(self):
        e=self.engine();t=self.submit(e,'/limited')
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,failed.state);self.assertEqual(429,failed.http_status)
        self.assertEqual(9,failed.error);self.assertEqual(1,failed.request_count)

    def test_same_target_cannot_be_submitted_twice_while_active(self):
        e=self.engine();t=self.submit(e,'/slow')
        options=TaskOptions(C.sizeof(TaskOptions),ABI,(self.url+'/file').encode(),str(self.path/'task.bin').encode(),b'other-key',None,None,0,1,262144,1000,1500)
        other=C.c_uint64()
        self.assertEqual(3,self.lib.td_task_submit(e,C.byref(options),C.byref(other)))

    def test_preexisting_unowned_file_is_preserved(self):
        target=self.path/'task.bin';target.write_bytes(b'personal file')
        e=self.engine();t=self.submit(e)
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,failed.state)
        self.assertEqual(b'personal file',target.read_bytes())

    def test_changed_local_prefix_without_expected_digest_is_rejected(self):
        e=self.engine();t=self.submit(e,'/slow',sha=False)
        self.until(e,t,lambda s:s.downloaded_bytes>=131072)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));self.until(e,t,lambda s:s.state==PAUSED)
        with (self.path/'task.bin').open('r+b') as f:f.seek(100);f.write(b'CORRUPTED')
        self.assertEqual(0,self.lib.td_task_control(e,t,2))
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,failed.state,'local corruption must not be published as complete')

    def test_killed_process_recovers_only_durable_prefix(self):
        script=Path(__file__).with_name('crash_child.py')
        child=subprocess.Popen([sys.executable,str(script),str(self.path),self.url+'/slow'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            line=child.stdout.readline()
            self.assertTrue(line.startswith('DURABLE '),line)
            durable=int(line.split()[1]);self.assertGreater(durable,0)
            child.kill();child.wait(timeout=5)
        finally:
            if child.poll() is None:child.kill();child.wait(timeout=5)
            child.stdout.close();child.stderr.close()
        e=self.engine();t=self.submit(e,'/slow')
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertLess(done.wire_bytes,len(DATA))
        self.assertLessEqual(done.wire_bytes,len(DATA)-durable)
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_parallel_ranges_use_real_concurrency_and_preserve_sha(self):
        e=self.engine(connections=4);t=self.submit(e,'/slow',connections=4)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertGreaterEqual(done.peak_active_requests,2)
        self.assertLessEqual(done.peak_active_requests,4)
        with Fixture.lock:peak=Fixture.peak
        self.assertGreaterEqual(peak,2,'fixture must observe parallel requests')
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_parallel_pause_and_process_recreation_preserve_disjoint_extents(self):
        e=self.engine(connections=4);t=self.submit(e,'/sparse',connections=4)
        self.until(e,t,lambda s:s.downloaded_bytes>=400000)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));paused=self.until(e,t,lambda s:s.state==PAUSED)
        self.assertGreater(paused.durable_bytes,0)
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        e=self.engine(connections=4);t=self.submit(e,'/sparse',connections=4)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(len(DATA)-paused.durable_bytes,done.wire_bytes)
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_completed_checkpoint_reopens_without_network_transfer(self):
        e=self.engine();t=self.submit(e)
        self.until(e,t,lambda s:s.state==COMPLETED)
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        e=self.engine();t=self.submit(e)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(0,done.wire_bytes)

    def test_new_file_without_validator_or_expected_digest_still_downloads(self):
        e=self.engine();t=self.submit(e,'/no-validator',sha=False)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_short_read_retries_missing_bytes_without_bad_completion(self):
        e=self.engine();t=self.submit(e,'/short-once',connections=3)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        with Fixture.lock:requests=list(Fixture.requests)
        self.assertTrue(any(r[1] and r[1].startswith('bytes=32768-') for r in requests))
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_clients_share_real_connection_budget(self):
        first=self.engine(connections=2);second=self.engine(connections=2)
        a=self.submit(first,'/slow',key='one',connections=4)
        b=self.submit(second,'/slow',key='two',connections=4)
        peak=0;deadline=time.monotonic()+10
        while time.monotonic()<deadline:
            sa=self.query(first,a);sb=self.query(second,b)
            peak=max(peak,sa.active_requests+sb.active_requests)
            if sa.state in (COMPLETED,FAILED) and sb.state in (COMPLETED,FAILED):break
            time.sleep(.005)
        self.assertEqual(COMPLETED,sa.state,sa.message);self.assertEqual(COMPLETED,sb.state,sb.message)
        self.assertLessEqual(peak,2)
        self.assertEqual(DATA,(self.path/'one.bin').read_bytes());self.assertEqual(DATA,(self.path/'two.bin').read_bytes())

    def test_small_payload_buffer_budget_restricts_actual_parallelism(self):
        e=self.engine(connections=8,buffers=81920);t=self.submit(e,'/slow',connections=8)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(1,done.peak_active_requests)

    def test_failed_source_can_be_replaced_without_losing_task_identity(self):
        e=self.engine();t=self.submit(e,'/expired')
        failed=self.until(e,t,lambda s:s.state==FAILED)
        self.assertEqual(403,failed.http_status)
        source=SourceOptions(C.sizeof(SourceOptions),ABI,(self.url+'/file').encode(),None,0,0)
        self.assertEqual(0,self.lib.td_task_replace_source(e,t,C.byref(source)))
        self.assertEqual(0,self.lib.td_task_control(e,t,2))
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED) and s.generation==2)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'task.bin').read_bytes())

    def test_forgetting_inactive_task_retains_checkpoint_and_allows_resubmission(self):
        e=self.engine();t=self.submit(e)
        self.until(e,t,lambda s:s.state==COMPLETED)
        self.assertEqual(0,self.lib.td_task_forget(e,t))
        s=Snapshot(C.sizeof(Snapshot),ABI)
        self.assertEqual(2,self.lib.td_task_query(e,t,C.byref(s)))
        t=self.submit(e)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(0,done.wire_bytes)

    def test_global_speed_limit_is_shared_by_tasks_and_can_be_disabled(self):
        e=self.engine(slots=2,connections=4)
        self.assertEqual(0,self.lib.td_engine_set_speed_limit(e,128*1024))
        a=self.submit(e,key='limit-one',connections=2);b=self.submit(e,key='limit-two',connections=2)
        time.sleep(.7)
        first=self.query(e,a);second=self.query(e,b)
        self.assertLess(first.downloaded_bytes+second.downloaded_bytes,200000,'limit must not multiply by task count')
        self.assertEqual(0,self.lib.td_engine_set_speed_limit(e,0))
        da=self.until(e,a,lambda s:s.state in (COMPLETED,FAILED));db=self.until(e,b,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,da.state,da.message);self.assertEqual(COMPLETED,db.state,db.message)

    def test_source_byte_range_is_projected_into_its_own_file(self):
        e=self.engine();projected=DATA[12345:456789]
        slice=RequestSlice(C.sizeof(RequestSlice),ABI,12345,len(projected))
        o=ProjectedOptions();o.struct_size=C.sizeof(o);o.abi_version=ABI;o.url=(self.url+'/file').encode();o.destination=str(self.path/'projected.bin').encode();o.stable_key=b'projected';o.expected_sha256=hashlib.sha256(projected).hexdigest().encode();o.connections=3;o.request_window_bytes=131072;o.connect_timeout_ms=1000;o.read_timeout_ms=1500;o.slice=C.pointer(slice)
        tid=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.cast(C.byref(o),C.POINTER(TaskOptions)),C.byref(tid)))
        done=self.until(e,tid.value,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(len(projected),done.total_bytes)
        self.assertEqual(projected,(self.path/'projected.bin').read_bytes())

    def test_response_size_limit_stops_unbounded_manifest_download(self):
        e=self.engine();o=ProjectedOptions();o.struct_size=C.sizeof(o);o.abi_version=ABI;o.url=(self.url+'/ignore-range').encode();o.destination=str(self.path/'limited.bin').encode();o.stable_key=b'limited-body';o.connections=1;o.request_window_bytes=262144;o.maximum_response_bytes=65536
        tid=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.cast(C.byref(o),C.POINTER(TaskOptions)),C.byref(tid)))
        done=self.until(e,tid.value,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,done.state);self.assertEqual(10,done.error)
        self.assertLessEqual((self.path/'limited.bin').stat().st_size,65536)

if __name__=='__main__':unittest.main(verbosity=2)
