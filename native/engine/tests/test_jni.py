import hashlib
import os
from pathlib import Path
import subprocess
import unittest
from test_engine import DATA,EngineCase

class JniTests(EngineCase):
    def test_jvm_metalink_preserves_host_policy_and_validates_file(self):
        java=Path(os.environ['TD_JAVA_HOME'])/'bin'/'java.exe'
        result=subprocess.run([str(java),'-cp',os.environ['TD_JAVA_CLASSES'],'NativeMetalinkSmoke',os.environ['TD_ENGINE_LIBRARY'],str(self.path),self.url+'/slow',hashlib.sha256(DATA).hexdigest()],capture_output=True,text=True,timeout=20)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertIn('JNI_METALINK_SHA_AND_POLICY_OK',result.stdout)

    def test_jvm_download_uses_native_core_and_unicode_file_path(self):
        java=Path(os.environ['TD_JAVA_HOME'])/'bin'/'java.exe'
        result=subprocess.run([str(java),'-cp',os.environ['TD_JAVA_CLASSES'],'NativeBridgeSmoke',os.environ['TD_ENGINE_LIBRARY'],str(self.path),self.url+'/slow',hashlib.sha256(DATA).hexdigest()],capture_output=True,text=True,timeout=20)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertIn('JNI_DOWNLOAD_SHA_OK',result.stdout)
        self.assertIn('JNI_CLOSED_HANDLE_OK',result.stdout)

if __name__=='__main__':unittest.main(verbosity=2)
