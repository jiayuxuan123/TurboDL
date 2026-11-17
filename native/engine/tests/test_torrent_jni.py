import hashlib
import os
from pathlib import Path
import subprocess
import unittest
from test_engine import DATA
from test_torrent import TorrentCase

class TorrentJniTests(TorrentCase):
    def test_jvm_magnet_transfer_uses_shared_native_task_lifecycle(self):
        result=subprocess.run([
            str(Path(os.environ['TD_JAVA_HOME'])/'bin'/'java.exe'),
            '-cp',os.environ['TD_JAVA_CLASSES'],'NativeTorrentSmoke',
            os.environ['TD_ENGINE_LIBRARY'],str(self.path),self.magnet,
            str(self.peer_port),hashlib.sha256(DATA).hexdigest(),
        ],capture_output=True,text=True,timeout=30)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertIn('JNI_MAGNET_SHA_OK',result.stdout)

if __name__=='__main__':unittest.main(verbosity=2)
