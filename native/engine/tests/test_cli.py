import hashlib
import json
import os
from pathlib import Path
import subprocess
import unittest
from test_engine import DATA,EngineCase

class CliTests(EngineCase):
    def executable(self):return str(Path(os.environ['TD_ENGINE_LIBRARY']).parent/'tdl-vnext.exe')
    def test_cli_unicode_output_and_checkpoint_paths(self):
        output=self.path/'下载-🌤.bin'
        command=[self.executable(),self.url+'/file','-o',str(output),'--checkpoint-dir',str(self.path/'断点'),'--sha256',hashlib.sha256(DATA).hexdigest(),'--allow-loopback-fixture','--timeout','10']
        result=subprocess.run(command,capture_output=True,encoding='utf-8',timeout=15)
        self.assertEqual(0,result.returncode,result.stderr+result.stdout[-500:])
        self.assertEqual(DATA,output.read_bytes())
        final=json.loads(result.stdout.splitlines()[-1]);self.assertEqual(6,final['state'])
    def test_cli_metalink_enters_real_download_lifecycle(self):
        document=self.path/'fixture.meta4';output=self.path/'selected.bin'
        document.write_text(f'<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="fixture.bin"><size>{len(DATA)}</size><hash type="sha-256">{hashlib.sha256(DATA).hexdigest()}</hash><url>{self.url}/file</url></file></metalink>',encoding='utf-8')
        result=subprocess.run([self.executable(),'--metalink',str(document),'-o',str(output),'--allow-loopback-fixture','--timeout','10'],capture_output=True,encoding='utf-8',timeout=15)
        self.assertEqual(0,result.returncode,result.stderr+result.stdout[-500:])
        self.assertEqual(DATA,output.read_bytes())

if __name__=='__main__':unittest.main(verbosity=2)
