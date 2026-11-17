"""Synthetic accounts only; verifies refreshed sources cannot inherit credentials."""
import ctypes as C
import hashlib
import http.server
import threading
import unittest
from test_engine import ABI,DATA,COMPLETED,FAILED,EngineCase,Fixture,NetworkOptions,ExtendedOptions,TaskOptions,SourceOptions

class CredentialTests(EngineCase):
    def test_source_refresh_to_another_origin_does_not_reuse_basic_auth(self):
        self.refresh_case(same_origin=False)
    def test_same_origin_refresh_keeps_explicit_account_authentication(self):
        self.refresh_case(same_origin=True)
    def test_refresh_cannot_bypass_sftp_known_host_configuration(self):
        e=self.engine();task=self.submit(e,'/expired',key='protocol-scope')
        self.until(e,task,lambda s:s.state==FAILED)
        source=SourceOptions(C.sizeof(SourceOptions),ABI,b'sftp://example.invalid/file',None,0,0)
        self.assertEqual(5,self.lib.td_task_replace_source(e,task,C.byref(source)))

    def refresh_case(self,same_origin):
        for port in range(8825,8841):
            try:mirror=http.server.ThreadingHTTPServer(('127.0.0.1',port),Fixture);break
            except OSError:continue
        else:raise RuntimeError('no second-origin fixture port')
        mirror.daemon_threads=True;thread=threading.Thread(target=mirror.serve_forever,daemon=True);thread.start()
        try:
            e=self.engine()
            network=NetworkOptions();network.struct_size=C.sizeof(network);network.abi_version=ABI
            network.username=b'fixture-account';network.password=b'fixture-only'
            options=ExtendedOptions();options.struct_size=C.sizeof(options);options.abi_version=ABI
            options.url=(self.url+'/expired').encode();options.destination=str(self.path/'credentials.bin').encode();options.stable_key=b'credential-refresh'
            options.expected_sha256=hashlib.sha256(DATA).hexdigest().encode();options.connections=2;options.request_window_bytes=262144
            options.connect_timeout_ms=1000;options.read_timeout_ms=1500;options.network=C.pointer(network)
            tid=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.cast(C.byref(options),C.POINTER(TaskOptions)),C.byref(tid)))
            self.until(e,tid.value,lambda s:s.state==FAILED)
            with Fixture.lock:
                self.assertTrue(any(row[3] is not None for row in Fixture.requests),'initial origin must exercise Basic auth')
                Fixture.requests.clear()
            target=self.url if same_origin else f'http://127.0.0.1:{mirror.server_port}'
            source=SourceOptions(C.sizeof(SourceOptions),ABI,(target+'/file').encode(),None,0,0)
            self.assertEqual(0,self.lib.td_task_replace_source(e,tid.value,C.byref(source)))
            self.assertEqual(0,self.lib.td_task_control(e,tid.value,2))
            done=self.until(e,tid.value,lambda s:s.state in (COMPLETED,FAILED) and s.generation==2)
            self.assertEqual(COMPLETED,done.state,done.message)
            self.assertEqual(DATA,(self.path/'credentials.bin').read_bytes())
            with Fixture.lock:requests=list(Fixture.requests)
            self.assertTrue(requests)
            self.assertTrue(all((row[3] is not None)==same_origin for row in requests),'source authentication scope was not preserved')
        finally:mirror.shutdown();mirror.server_close();thread.join(2)

if __name__=='__main__':unittest.main(verbosity=2)
