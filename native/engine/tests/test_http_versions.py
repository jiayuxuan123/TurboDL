"""Real TLS/ALPN and QUIC protocol negotiation, not feature-string assertions."""
import asyncio
import ctypes as C
import datetime
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import ssl
import subprocess
import threading
import unittest
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from test_engine import ABI,DATA,COMPLETED,FAILED,EngineCase,NetworkOptions,Policy,ExtendedOptions,TaskOptions,HttpMetrics
from test_protocols import reserve_port

def certificate(directory):
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'local H2 H3 fixture')])
    now=datetime.datetime.now(datetime.timezone.utc)
    cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(now-datetime.timedelta(days=1)).not_valid_after(now+datetime.timedelta(days=1))
          .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),False)
          .add_extension(x509.BasicConstraints(ca=True,path_length=None),True).sign(key,hashes.SHA256()))
    public=directory/'ca.pem';private=directory/'key.pem'
    public.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    return public,private

def response(headers):
    fields={k.decode() if isinstance(k,bytes) else k:v.decode() if isinstance(v,bytes) else v for k,v in headers}
    raw=fields.get('range','bytes=0-').removeprefix('bytes=')
    begin,end=raw.split('-');begin=int(begin);end=min(int(end) if end else len(DATA)-1,len(DATA)-1)
    body=DATA[begin:end+1]
    return [(b':status',b'206'),(b'content-length',str(len(body)).encode()),(b'content-range',f'bytes {begin}-{end}/{len(DATA)}'.encode()),(b'etag',b'"h2-h3-fixture"')],body

class HttpVersionTests(EngineCase):
    def setUp(self):
        super().setUp();self.ca,self.private=certificate(self.path)
        self.loop=asyncio.new_event_loop();self.loop_errors=[]
        self.loop.set_exception_handler(lambda loop,context:self.loop_errors.append(str(context)))
        self.loop_thread=threading.Thread(target=self.loop.run_forever,daemon=True);self.loop_thread.start()
        self.services=[];self.writers=set();self.seen=[]
    def tearDown(self):
        try:
            for handle in self.engines:self.assertEqual(0,self.lib.td_engine_destroy(handle))
            self.engines.clear()
            async def shutdown():
                for writer in list(self.writers):writer.close()
                for service in self.services:
                    service.close()
                    if hasattr(service,'wait_closed'):await service.wait_closed()
                await asyncio.sleep(.1)
            asyncio.run_coroutine_threadsafe(shutdown(),self.loop).result(timeout=5)
            self.loop.call_soon_threadsafe(self.loop.stop);self.loop_thread.join(5);self.loop.close()
            self.assertFalse(self.loop_errors,self.loop_errors)
        finally:super().tearDown()
    def submit_version(self,e,port,version):
        network=NetworkOptions();network.struct_size=C.sizeof(network);network.abi_version=ABI;network.ca_bundle=str(self.ca).encode();network.http_version=version
        policy=Policy(C.sizeof(Policy),ABI,0,4,None)
        options=ExtendedOptions();options.struct_size=C.sizeof(options);options.abi_version=ABI
        options.url=f'https://127.0.0.1:{port}/file'.encode();options.destination=str(self.path/f'http{version}.bin').encode();options.stable_key=f'http{version}'.encode()
        options.expected_sha256=hashlib.sha256(DATA).hexdigest().encode();options.connections=4;options.request_window_bytes=262144
        options.connect_timeout_ms=3000;options.read_timeout_ms=5000;options.network=C.pointer(network);options.policy=C.pointer(policy)
        task=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.cast(C.byref(options),C.POINTER(TaskOptions)),C.byref(task)))
        return task.value
    def verify(self,e,task,version):
        done=self.until(e,task,lambda s:s.state in (COMPLETED,FAILED),timeout=15)
        self.assertEqual(COMPLETED,done.state,done.message)
        self.assertEqual(DATA,(self.path/f'http{version}.bin').read_bytes())
        metrics=HttpMetrics();metrics.struct_size=C.sizeof(metrics);metrics.abi_version=ABI
        self.assertEqual(0,self.lib.td_task_http_metrics(e,task,C.byref(metrics)))
        self.assertEqual(1<<(version-1),metrics.versions,'a silent protocol downgrade is not a negotiation pass')
        self.assertGreater(metrics.finished_requests,1);self.assertGreater(metrics.new_connections,0)
        self.assertGreater(metrics.task_first_byte_us,0);self.assertEqual(0,metrics.active_streams)
        self.assertTrue(self.seen)
    def verify_cli(self, port, version):
        name = 'tdl-vnext.exe' if os.name == 'nt' else 'tdl-vnext'
        cli = os.environ.get('TD_PUBLIC_CLI') or str(Path(os.environ['TD_ENGINE_LIBRARY']).with_name(name))
        output = self.path / f'cli-http{version}.bin'
        result = subprocess.run(
            [cli, f'https://127.0.0.1:{port}/file', '-o', str(output), '--connections', '4',
             '--http-policy', f'http{version}', '--ca-bundle', str(self.ca),
             '--sha256', hashlib.sha256(DATA).hexdigest(), '--deadline', '15', '--allow-loopback-fixture'],
            capture_output=True, text=True, encoding='utf-8', timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout[-800:])
        final = json.loads(result.stdout.splitlines()[-1])
        self.assertEqual(final['negotiated_http_versions'], 1 << (version - 1))
        self.assertEqual(output.read_bytes(), DATA)
    def test_http2_ranges_use_real_tls_alpn_and_h2_frames(self):
        import h2.config,h2.connection,h2.events
        async def handle(reader,writer):
            self.writers.add(writer)
            protocol=h2.connection.H2Connection(config=h2.config.H2Configuration(client_side=False))
            protocol.initiate_connection();writer.write(protocol.data_to_send());await writer.drain()
            pending={}
            def pump():
                for stream,(body,offset) in list(pending.items()):
                    while offset<len(body):
                        size=min(len(body)-offset,protocol.max_outbound_frame_size,protocol.local_flow_control_window(stream))
                        if size<=0:break
                        protocol.send_data(stream,body[offset:offset+size],end_stream=offset+size==len(body));offset+=size
                    if offset==len(body):del pending[stream]
                    else:pending[stream]=(body,offset)
            try:
                while raw:=await reader.read(65536):
                    for event in protocol.receive_data(raw):
                        if isinstance(event,h2.events.RequestReceived):
                            self.seen.append(event.headers);headers,body=response(event.headers)
                            protocol.send_headers(event.stream_id,headers);pending[event.stream_id]=(body,0)
                        elif isinstance(event,h2.events.StreamReset):pending.pop(event.stream_id,None)
                    pump();writer.write(protocol.data_to_send());await writer.drain()
            except (ConnectionError,ssl.SSLError):pass
            finally:self.writers.discard(writer);writer.close()
        async def start():
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(self.ca,self.private);context.set_alpn_protocols(['h2'])
            server=await asyncio.start_server(handle,'127.0.0.1',reserve_port(self),ssl=context);self.services.append(server)
            return server.sockets[0].getsockname()[1]
        port=asyncio.run_coroutine_threadsafe(start(),self.loop).result(timeout=5)
        e=self.engine();self.verify(e,self.submit_version(e,port,2),2)
        self.verify_cli(port, 2)
    def test_http3_ranges_use_real_quic_and_h3_streams(self):
        from aioquic.asyncio import serve
        from aioquic.asyncio.protocol import QuicConnectionProtocol
        from aioquic.h3.connection import H3Connection,H3_ALPN
        from aioquic.h3.events import HeadersReceived
        from aioquic.quic.configuration import QuicConfiguration
        owner=self
        class Server(QuicConnectionProtocol):
            def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self.http=H3Connection(self._quic)
            def quic_event_received(self,event):
                for request in self.http.handle_event(event):
                    if isinstance(request,HeadersReceived):
                        owner.seen.append(request.headers);headers,body=response(request.headers)
                        self.http.send_headers(request.stream_id,headers)
                        self.http.send_data(request.stream_id,body,end_stream=True);self.transmit()
        async def start():
            config=QuicConfiguration(is_client=False,alpn_protocols=H3_ALPN,max_data=16*1024*1024,max_stream_data=2*1024*1024)
            config.load_cert_chain(self.ca,self.private)
            port=reserve_port(self)
            server=await serve('127.0.0.1',port,configuration=config,create_protocol=Server);self.services.append(server);return port
        port=asyncio.run_coroutine_threadsafe(start(),self.loop).result(timeout=5)
        e=self.engine();self.verify(e,self.submit_version(e,port,3),3)
        self.verify_cli(port, 3)

if __name__=='__main__':unittest.main(verbosity=2)
