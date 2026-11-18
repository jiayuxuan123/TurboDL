"""Real TLS/FTP/SFTP fixture services; optional packages are test-only dependencies."""
import ctypes as C
import datetime
import hashlib
import http.server
import ipaddress
import logging
import os
from pathlib import Path
import socket
import ssl
import subprocess
import threading
import unittest
from test_engine import ABI,DATA,COMPLETED,FAILED,Fixture,EngineCase,NetworkOptions,Policy,ExtendedOptions,TaskOptions
TEST_PASSWORD=hashlib.sha256(b'local protocol fixture authentication').hexdigest()

def reserve_port(case):
    while type(case).next_port<=8840:
        port=type(case).next_port;type(case).next_port+=1
        s=socket.socket()
        try:s.bind(('127.0.0.1',port));s.close();return port
        except OSError:s.close()
    raise RuntimeError('fixture control port range exhausted')

class ProtocolTests(EngineCase):
    def submit_network(self,e,url,network,policy=None,key='protocol'):
        o=ExtendedOptions()
        o.struct_size=C.sizeof(o);o.abi_version=ABI;o.url=url.encode()
        o.destination=str(self.path/(key+'.bin')).encode();o.stable_key=key.encode()
        o.expected_sha256=hashlib.sha256(DATA).hexdigest().encode();o.connections=1
        o.request_window_bytes=262144;o.connect_timeout_ms=1500;o.read_timeout_ms=2000
        o.network=C.pointer(network)
        if policy is not None:o.policy=C.pointer(policy)
        tid=C.c_uint64();self.assertEqual(0,self.lib.td_task_submit(e,C.cast(C.byref(o),C.POINTER(TaskOptions)),C.byref(tid)));return tid.value

    def network(self,**fields):
        n=NetworkOptions();n.struct_size=C.sizeof(n);n.abi_version=ABI
        for key,value in fields.items():setattr(n,key,str(value).encode())
        return n

    def test_tls_uses_supplied_ca_and_verifies_hostname(self):
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes,serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'local protocol fixture')])
        now=datetime.datetime.now(datetime.timezone.utc)
        certificate=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),False).add_extension(x509.BasicConstraints(ca=True,path_length=None),True).sign(key,hashes.SHA256()))
        cert=self.path/'fixture-ca.pem';private=self.path/'fixture-key.pem'
        cert.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
        private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        server=http.server.ThreadingHTTPServer(('127.0.0.1',reserve_port(self)),Fixture);server.daemon_threads=True
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,private)
        server.socket=context.wrap_socket(server.socket,server_side=True)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            e=self.engine();t=self.submit_network(e,f'https://127.0.0.1:{server.server_port}/file',self.network(ca_bundle=cert))
            done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
            self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(DATA,(self.path/'protocol.bin').read_bytes())
            policy=Policy(C.sizeof(Policy),ABI,0,1,None)
            t=self.submit_network(e,f'https://localhost:{server.server_port}/file',self.network(ca_bundle=cert),policy,key='bad-hostname')
            failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
            self.assertEqual(FAILED,failed.state,'certificate without localhost SAN must be rejected')
        finally:server.shutdown();server.server_close();thread.join(2)

    def test_ftp_password_login_and_complete_digest(self):
        from pyftpdlib.authorizers import DummyAuthorizer
        from pyftpdlib.handlers import FTPHandler
        from pyftpdlib.servers import FTPServer
        from pyftpdlib.ioloop import IOLoop
        logging.getLogger('pyftpdlib').setLevel(logging.CRITICAL)
        source=self.path/'ftp-source';source.mkdir();(source/'fixture.bin').write_bytes(DATA)
        authorizer=DummyAuthorizer();authorizer.add_user('fixture',TEST_PASSWORD,str(source),perm='elr')
        class Handler(FTPHandler):pass
        Handler.authorizer=authorizer;Handler.passive_ports=[8848,8849,8850]
        loop=IOLoop();port=reserve_port(self);server=FTPServer(('127.0.0.1',port),Handler,ioloop=loop)
        thread=threading.Thread(target=lambda:server.serve_forever(timeout=.05,handle_exit=False),daemon=True);thread.start()
        try:
            e=self.engine();t=self.submit_network(e,f'ftp://127.0.0.1:{port}/fixture.bin',self.network(username='fixture',password=TEST_PASSWORD))
            done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED))
            self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(DATA,(self.path/'protocol.bin').read_bytes())
        finally:server.close_all();loop.close();thread.join(2)

    def test_sftp_password_login_requires_matching_known_host(self):
        import paramiko
        logging.getLogger('paramiko').setLevel(logging.CRITICAL)
        source=self.path/'sftp-fixture.bin';source.write_bytes(DATA)
        class Authentication(paramiko.ServerInterface):
            def check_auth_password(self,user,password):return paramiko.AUTH_SUCCESSFUL if user=='fixture' and password==TEST_PASSWORD else paramiko.AUTH_FAILED
            def get_allowed_auths(self,user):return 'password'
            def check_channel_request(self,kind,chanid):return paramiko.OPEN_SUCCEEDED if kind=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        class ReadOnlySftp(paramiko.SFTPServerInterface):
            def stat(self,path):return paramiko.SFTPAttributes.from_stat(source.stat()) if path=='/fixture.bin' else paramiko.SFTP_NO_SUCH_FILE
            def lstat(self,path):return self.stat(path)
            def open(self,path,flags,attr):
                if path!='/fixture.bin' or flags&(os.O_WRONLY|os.O_RDWR):return paramiko.SFTP_PERMISSION_DENIED
                handle=paramiko.SFTPHandle(flags);handle.readfile=source.open('rb');return handle
        key=paramiko.RSAKey.generate(2048);port=reserve_port(self)
        known=self.path/'known_hosts';known.write_text(f'[127.0.0.1]:{port} {key.get_name()} {key.get_base64()}\n',encoding='ascii')
        listener=socket.socket();listener.bind(('127.0.0.1',port));listener.listen(8);listener.settimeout(.1)
        stopped=threading.Event();transports=[]
        def accept():
            while not stopped.is_set():
                try:connection,_=listener.accept()
                except socket.timeout:continue
                except OSError:return
                transport=paramiko.Transport(connection);transports.append(transport);transport.add_server_key(key)
                transport.set_subsystem_handler('sftp',paramiko.SFTPServer,ReadOnlySftp)
                try:transport.start_server(server=Authentication())
                except paramiko.SSHException:transport.close()
        thread=threading.Thread(target=accept,daemon=True);thread.start()
        try:
            e=self.engine();network=self.network(username='fixture',password=TEST_PASSWORD,ssh_known_hosts=known)
            t=self.submit_network(e,f'sftp://127.0.0.1:{port}/fixture.bin',network)
            done=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=12)
            self.assertEqual(COMPLETED,done.state,done.message);self.assertEqual(DATA,(self.path/'protocol.bin').read_bytes())
            name = 'tdl-vnext.exe' if os.name == 'nt' else 'tdl-vnext'
            cli = os.environ.get('TD_PUBLIC_CLI') or str(Path(os.environ['TD_ENGINE_LIBRARY']).with_name(name))
            output = self.path / 'cli-sftp.bin'
            result = subprocess.run(
                [cli, f'sftp://127.0.0.1:{port}/fixture.bin', '-o', str(output),
                 '--username', 'fixture', '--password', TEST_PASSWORD, '--ssh-known-hosts', str(known),
                 '--sha256', hashlib.sha256(DATA).hexdigest(), '--connections', '1',
                 '--deadline', '12', '--allow-loopback-fixture'],
                capture_output=True, text=True, encoding='utf-8', timeout=16,
            )
            self.assertEqual(0, result.returncode, result.stderr + result.stdout[-800:])
            self.assertEqual(DATA, output.read_bytes())
            other=paramiko.RSAKey.generate(2048);known.write_text(f'[127.0.0.1]:{port} {other.get_name()} {other.get_base64()}\n',encoding='ascii')
            t=self.submit_network(e,f'sftp://127.0.0.1:{port}/fixture.bin',network,Policy(C.sizeof(Policy),ABI,0,1,None),key='wrong-host-key')
            failed=self.until(e,t,lambda s:s.state in (COMPLETED,FAILED),timeout=8)
            self.assertEqual(FAILED,failed.state,'a different SSH host key must be rejected')
        finally:
            stopped.set();listener.close();thread.join(2)
            for transport in transports:transport.close()

if __name__=='__main__':unittest.main(verbosity=2)
