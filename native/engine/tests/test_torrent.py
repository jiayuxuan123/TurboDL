import ctypes as C
import os
from pathlib import Path
import subprocess
import socket
import time
import unittest
from test_engine import ABI,DATA,COMPLETED,FAILED,PAUSED,CANCELLED,EngineCase

class TorrentOptions(C.Structure):
    _fields_=[('struct_size',C.c_uint32),('abi_version',C.c_uint32),('source',C.c_char_p),('destination',C.c_char_p),('stable_key',C.c_char_p),('peer_limit',C.c_uint32),('seed_seconds',C.c_uint32),('selected_files',C.POINTER(C.c_int32)),('selected_file_count',C.c_uint32),('reserved',C.c_uint32)]
class TorrentLimits(C.Structure):
    _fields_=TorrentOptions._fields_+[('download_limit',C.c_uint64),('upload_limit',C.c_uint64)]

class TorrentCase(EngineCase):
    def setUp(self):
        super().setUp()
        self.lib.td_torrent_submit.argtypes=[C.c_void_p,C.POINTER(TorrentOptions),C.POINTER(C.c_uint64)]
        self.lib.td_torrent_connect_peer.argtypes=[C.c_void_p,C.c_uint64,C.c_char_p,C.c_uint16]
        self.start_seed()
    def start_seed(self,multi=False):
        if hasattr(self,'seed'):
            self.seed.terminate();self.seed.wait(timeout=5);self.seed.stdout.close();self.seed.stderr.close()
        source=self.path/'seed';source.mkdir(exist_ok=True);(source/'fixture.bin').write_bytes(DATA)
        if multi:
            (source/'bundle').mkdir();(source/'bundle'/'first.bin').write_bytes(DATA)
            (source/'bundle'/'second.bin').write_bytes(DATA[::-1])
        self.torrent=self.path/'fixture.torrent'
        for port in range(8846,8851):
            probe=socket.socket()
            try:probe.bind(('127.0.0.1',port));self.peer_port=port;break
            except OSError:continue
            finally:probe.close()
        else:raise RuntimeError('no torrent fixture port available')
        args=[os.environ['TD_TORRENT_FIXTURE'],str(source),str(self.peer_port),str(self.torrent)]+(['multi'] if multi else [])
        self.seed=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        line=self.seed.stdout.readline()
        self.assertTrue(line.startswith('TORRENT_FIXTURE_READY '),line)
        self.magnet=line.split(maxsplit=2)[2].strip()
    def tearDown(self):
        try:
            for handle in self.engines:self.assertEqual(0,self.lib.td_engine_destroy(handle))
            self.engines.clear()
        finally:
            if self.seed.poll() is None:self.seed.terminate();self.seed.wait(timeout=5)
            self.seed.stdout.close();self.seed.stderr.close()
            super().tearDown()
    def torrent_task(self,e,key='torrent',magnet=False,selected=(),download_limit=0,seed_seconds=0):
        indices=(C.c_int32*len(selected))(*selected) if selected else None
        options=TorrentLimits(C.sizeof(TorrentLimits),ABI,(self.magnet if magnet else str(self.torrent)).encode(),str(self.path/'download').encode(),key.encode(),2,seed_seconds,indices,len(selected),0,download_limit,0)
        task=C.c_uint64();self.assertEqual(0,self.lib.td_torrent_submit(e,C.cast(C.byref(options),C.POINTER(TorrentOptions)),C.byref(task)))
        self.assertEqual(0,self.lib.td_torrent_connect_peer(e,task,b'127.0.0.1',self.peer_port));return task.value

class TorrentTests(TorrentCase):
    def test_repeated_file_indices_are_a_single_selection(self):
        e=self.engine(connections=8);t=self.torrent_task(e,selected=(0,0))
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(len(DATA),done.total_bytes);self.assertEqual(len(DATA),done.downloaded_bytes)
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())
    def test_invalid_file_selection_is_policy_failure_and_releases_budget(self):
        e=self.engine(connections=8);t=self.torrent_task(e,selected=(99,))
        failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=10)
        self.assertEqual(FAILED,failed.state);self.assertEqual(10,failed.error)
        self.assertEqual(0,self.resources().tasks);self.assertEqual(0,self.resources().external_transfers)

    def test_torrent_download_produces_exact_fixture_file(self):
        e=self.engine(connections=8);t=self.torrent_task(e)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())

    def test_magnet_file_selection_keeps_unselected_file_absent(self):
        self.start_seed(multi=True)
        e=self.engine(connections=8);t=self.torrent_task(e,magnet=True,selected=(1,))
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA[::-1],(self.path/'download'/'bundle'/'second.bin').read_bytes())
        self.assertFalse((self.path/'download'/'bundle'/'first.bin').exists())
        self.assertEqual(len(DATA),done.downloaded_bytes)

    def test_global_limit_applies_to_bittorrent(self):
        e=self.engine(connections=8)
        self.assertEqual(0,self.lib.td_engine_set_speed_limit(e,512*1024))
        started=time.monotonic();t=self.torrent_task(e)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=25)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertGreater(time.monotonic()-started,3.2,'BT must not bypass the process rate cap')
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())

    def test_task_limit_remains_when_global_limit_is_removed(self):
        e=self.engine(connections=8)
        started=time.monotonic();t=self.torrent_task(e,download_limit=512*1024)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=25)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertGreater(time.monotonic()-started,3.2)
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())

    def test_cancel_during_seeding_releases_resources_without_deleting_files(self):
        e=self.engine(connections=8);t=self.torrent_task(e,seed_seconds=30)
        self.until(e,t,lambda s:s.downloaded_bytes==len(DATA),timeout=20)
        self.assertNotEqual(COMPLETED,self.query(e,t).state)
        self.assertEqual(0,self.lib.td_task_control(e,t,3))
        cancelled=self.until(e,t,lambda s:s.state in (CANCELLED,FAILED),timeout=8)
        self.assertEqual(CANCELLED,cancelled.state,cancelled.message)
        self.assertEqual(0,cancelled.active_requests)
        self.assertEqual(0,self.resources().external_transfers)
        http=self.submit(e,key='after-bt',connections=8)
        done=self.until(e,http,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())

    def test_http_and_bittorrent_share_rate_and_connection_admissions(self):
        e=self.engine(slots=2,connections=8)
        self.assertEqual(0,self.lib.td_engine_set_speed_limit(e,256*1024))
        t=self.torrent_task(e);http=self.submit(e,key='mixed-http',connections=2)
        time.sleep(1.8)
        a,b=self.query(e,t),self.query(e,http)
        self.assertLess(a.downloaded_bytes+b.downloaded_bytes,786432,'protocols must not each consume a full global allowance')
        self.assertLessEqual(self.resources().connections,8)
        self.assertEqual(0,self.lib.td_engine_set_speed_limit(e,0))
        a=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        b=self.until(e,http,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,a.state,a.message);self.assertEqual(COMPLETED,b.state,b.message)
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())
        self.assertEqual(DATA,(self.path/'mixed-http.bin').read_bytes())

    def test_modified_piece_is_rechecked_after_process_recreation(self):
        e=self.engine(connections=8);t=self.torrent_task(e)
        self.until(e,t,lambda s:s.downloaded_bytes>=524288,timeout=15)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));self.until(e,t,lambda s:s.state==PAUSED)
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        target=self.path/'download'/'fixture.bin'
        with target.open('r+b') as f:f.seek(100);f.write(b'damaged saved piece')
        e=self.engine(connections=8);t=self.torrent_task(e)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,target.read_bytes())
    def test_magnet_download_retrieves_metadata_and_exact_file(self):
        e=self.engine(connections=8);t=self.torrent_task(e,magnet=True)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())
    def test_torrent_pause_recreation_and_resume_preserve_valid_pieces(self):
        e=self.engine(connections=8);t=self.torrent_task(e)
        self.until(e,t,lambda s:s.downloaded_bytes>=262144,timeout=15)
        self.assertEqual(0,self.lib.td_task_control(e,t,1));paused=self.until(e,t,lambda s:s.state in (PAUSED,FAILED),timeout=8)
        self.assertEqual(PAUSED,paused.state,paused.message)
        self.assertGreater(paused.durable_bytes,0)
        self.assertEqual(0,self.lib.td_engine_destroy(e));self.engines.remove(e)
        e=self.engine(connections=8);t=self.torrent_task(e)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=20)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'download'/'fixture.bin').read_bytes())

if __name__=='__main__':unittest.main(verbosity=2)
