"""Independent origins and credentials exercised only through the public ABI."""
import ctypes as C
import hashlib
import http.server
import threading
import time
import unittest
from test_engine import (ABI,DATA,COMPLETED,FAILED,EngineCase,Fixture,Header,
                         TaskOptions,SourceOptions,ProjectedOptions,Policy)

class Source(C.Structure):
    _fields_=SourceOptions._fields_+[('representation_id',C.c_char_p)]

class MirrorOptions(C.Structure):
    _fields_=ProjectedOptions._fields_+[('mirrors',C.POINTER(Source)),('mirror_count',C.c_uint32),('reserved',C.c_uint32)]

class MirrorFixture(Fixture):
    def send_header(self,name,value):
        # ETags are scoped to an origin, not a global content identifier.
        if name.lower()=='etag':value='"different-origin-validator"'
        return super().send_header(name,value)
    def serve_get(self):
        if self.headers.get('Authorization')!='Bearer mirror-fixture':
            self.send_response(403);self.send_header('Content-Length','0');self.end_headers();return
        super().serve_get()

class MirrorTests(EngineCase):
    def setUp(self):
        super().setUp()
        for port in range(8825,8841):
            try:self.mirror=http.server.ThreadingHTTPServer(('127.0.0.1',port),MirrorFixture);break
            except OSError:continue
        else:raise RuntimeError('no mirror fixture port')
        self.mirror.daemon_threads=True
        self.mirror_thread=threading.Thread(target=self.mirror.serve_forever,daemon=True);self.mirror_thread.start()
    def tearDown(self):
        try:super().tearDown()
        finally:self.mirror.shutdown();self.mirror.server_close();self.mirror_thread.join(2)
    def mirrored(self,e,primary='/slow',expected=True,same_origin=False):
        primary_headers=(Header*1)(Header(b'Authorization',b'Bearer primary-fixture'))
        mirror_headers=(Header*1)(Header(b'Authorization',b'Bearer mirror-fixture'))
        mirror_url=(self.url+'/file') if same_origin else f'http://127.0.0.1:{self.mirror.server_port}/slow'
        mirrors=(Source*1)(Source(C.sizeof(Source),ABI,mirror_url.encode(),mirror_headers,1,0,None))
        o=MirrorOptions();o.struct_size=C.sizeof(o);o.abi_version=ABI;o.url=(self.url+primary).encode()
        o.destination=str(self.path/'mirrored.bin').encode();o.stable_key=b'mirrored'
        o.expected_sha256=hashlib.sha256(DATA).hexdigest().encode() if expected else None
        o.headers=primary_headers;o.header_count=1;o.connections=4;o.request_window_bytes=262144
        o.connect_timeout_ms=1000;o.read_timeout_ms=1500;o.mirrors=mirrors;o.mirror_count=1
        policy=Policy(C.sizeof(Policy),ABI,3,4,self.path.name.encode());o.policy=C.pointer(policy)
        task=C.c_uint64();result=self.lib.td_task_submit(e,C.cast(C.byref(o),C.POINTER(TaskOptions)),C.byref(task))
        return result,task.value
    def test_distinct_headers_and_validators_can_share_hash_verified_ranges(self):
        e=self.engine(connections=4);result,t=self.mirrored(e);self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'mirrored.bin').read_bytes())
        with Fixture.lock:requests=list(Fixture.requests)
        self.assertTrue(any(r[3]=='Bearer primary-fixture' for r in requests))
        self.assertTrue(any(r[3]=='Bearer mirror-fixture' for r in requests))
    def test_auth_failure_does_not_discard_healthy_mirror(self):
        e=self.engine();result,t=self.mirrored(e,primary='/expired');self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'mirrored.bin').read_bytes())
    def test_cooling_origin_does_not_block_an_independent_mirror(self):
        e=self.engine();result,t=self.mirrored(e,primary='/limited');self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'mirrored.bin').read_bytes())
    def test_size_or_etag_alone_does_not_authorize_mixing_sources(self):
        e=self.engine();result,_=self.mirrored(e,expected=False)
        self.assertEqual(1,result)
    def test_shared_origin_cooldown_expiry_wakes_waiting_mirror(self):
        e=self.engine();started=time.monotonic()
        result,t=self.mirrored(e,primary='/limited-once',same_origin=True);self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertLess(time.monotonic()-started,.9,'250 ms backoff must not become an extra idle-poll second')
        self.assertEqual(DATA,(self.path/'mirrored.bin').read_bytes())

if __name__=='__main__':unittest.main(verbosity=2)
