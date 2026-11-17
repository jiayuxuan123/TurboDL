import ctypes as C
import hashlib
import unittest
from test_engine import ABI,DATA,COMPLETED,FAILED,EngineCase,TaskOptions,Fixture

HASH=hashlib.sha256(DATA).hexdigest()

class MetalinkTests(EngineCase):
    def setUp(self):
        super().setUp()
        self.lib.td_metalink_submit.argtypes=[C.c_void_p,C.c_char_p,C.c_uint32,C.c_char_p,C.c_char_p,C.POINTER(TaskOptions),C.POINTER(C.c_uint64)]
    def submit_xml(self,e,xml,selected=None):
        raw=xml.encode()
        transfer=TaskOptions(C.sizeof(TaskOptions),ABI,None,str(self.path/'metalink.bin').encode(),b'metalink',None,None,0,4,262144,1000,1500)
        task=C.c_uint64()
        code=self.lib.td_metalink_submit(e,raw,len(raw),(self.url+'/manifest.meta4').encode(),selected.encode() if selected else None,C.byref(transfer),C.byref(task))
        return code,task.value
    def document(self,body):return '<metalink xmlns="urn:ietf:params:xml:ns:metalink">'+body+'</metalink>'
    def file(self,name='fixture.bin',size=len(DATA),urls='<url priority="1">/file</url>',hash=HASH):
        return f'<file name="{name}"><size>{size}</size><hash type="sha-256">{hash}</hash>{urls}</file>'
    def test_v4_relative_urls_and_failed_source_use_verified_mirror(self):
        e=self.engine();xml=self.document(self.file(urls='<url priority="1">/expired</url><url priority="2">/slow</url>'))
        result,t=self.submit_xml(e,xml);self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(HASH,done.sha256.decode());self.assertEqual(DATA,(self.path/'metalink.bin').read_bytes())
    def test_v3_verification_resources_and_selected_file(self):
        xml=f'<metalink xmlns="http://www.metalinker.org/" version="3.0"><files><file name="other"/><file name="fixture.bin"><size>{len(DATA)}</size><verification><hash type="sha256">{HASH}</hash></verification><resources><url preference="100">/file</url></resources></file></files></metalink>'
        e=self.engine();result,t=self.submit_xml(e,xml,'fixture.bin');self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(DATA,(self.path/'metalink.bin').read_bytes())
    def test_declared_size_mismatch_cannot_report_completion(self):
        e=self.engine();result,t=self.submit_xml(e,self.document(self.file(size=len(DATA)+1)));self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,done.state);self.assertEqual(5,done.error)
    def test_hash_mismatch_cannot_report_completion(self):
        e=self.engine();result,t=self.submit_xml(e,self.document(self.file(hash='0'*64)));self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(FAILED,done.state);self.assertEqual(4,done.error)
    def test_ambiguous_multifile_selection_does_not_start_network(self):
        e=self.engine();result,_=self.submit_xml(e,self.document(self.file('one')+self.file('two')))
        self.assertEqual(1,result);self.assertFalse(Fixture.requests)
    def test_no_full_hash_rejects_unverified_multisource(self):
        e=self.engine();xml=self.document('<file name="fixture"><url>/file</url><url>/slow</url></file>')
        result,_=self.submit_xml(e,xml);self.assertEqual(5,result)
    def test_external_entities_unsupported_scheme_and_excess_depth_are_rejected(self):
        cases=[
            '<!DOCTYPE metalink [<!ENTITY content SYSTEM "file:///not-readable">]>'+self.document(self.file()),
            self.document(self.file(urls='<url>file:///not-readable</url>')),
            self.document('<extension>'*20+self.file()+'</extension>'*20),
        ]
        e=self.engine()
        for xml in cases:
            with self.subTest(xml=xml[:45]):self.assertEqual(1,self.submit_xml(e,xml)[0])
        self.assertFalse(Fixture.requests)
    def test_xml_file_name_never_controls_output_path(self):
        e=self.engine();result,t=self.submit_xml(e,self.document(self.file(name='../../escaped.bin')));self.assertEqual(0,result)
        done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/'metalink.bin').read_bytes())

if __name__=='__main__':unittest.main(verbosity=2)
