"""Test-owned process killed by the parent after a public durable-progress event."""
import ctypes as C
import hashlib
import os
from pathlib import Path
import sys
import time
from test_engine import DATA,ABI,Options,TaskOptions,Snapshot,load

lib=load()
path=Path(sys.argv[1])
options=Options(C.sizeof(Options),ABI,os.fsencode(path/'journal'),1,1,8*1024*1024,1,0)
engine=C.c_void_p()
if lib.td_engine_create(C.byref(options),C.byref(engine))!=0:raise RuntimeError('engine create failed')
task=TaskOptions(C.sizeof(TaskOptions),ABI,sys.argv[2].encode(),str(path/'task.bin').encode(),b'task',hashlib.sha256(DATA).hexdigest().encode(),None,0,1,262144,1000,1500)
tid=C.c_uint64()
if lib.td_task_submit(engine,C.byref(task),C.byref(tid))!=0:raise RuntimeError('submit failed')
deadline=time.monotonic()+10
while time.monotonic()<deadline:
    s=Snapshot();s.struct_size=C.sizeof(s);s.abi_version=ABI
    if lib.td_task_query(engine,tid,C.byref(s))!=0:raise RuntimeError('query failed')
    if s.durable_bytes>=262144:
        print('DURABLE',s.durable_bytes,flush=True)
        time.sleep(15)
        raise RuntimeError('parent did not kill test process')
    if s.state in (6,7,8):raise RuntimeError(f'early terminal state {s.state}')
    time.sleep(.005)
raise RuntimeError('no durable progress')
