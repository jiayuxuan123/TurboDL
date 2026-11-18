"""Serialize local gates and comparisons with an OS-owned lock."""
import atexit
import errno
import os
from pathlib import Path


DEFAULT_LOCK_ROOT = Path(r"D:\CodexResearch\turbodl-rebuild-20261116")


def acquire_local_run_lock(purpose: str, root: Path = DEFAULT_LOCK_ROOT):
    root.mkdir(parents=True, exist_ok=True)
    path = root / "local-run.lease"
    stream = path.open("a+b")
    try:
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as error:
        stream.close()
        if error.errno in (errno.EACCES, errno.EAGAIN):
            raise RuntimeError(f"{purpose}: another local run holds {path}") from error
        raise
    # Keep the same inode between runs. The kernel releases the lock on exit,
    # including when a test process crashes before Python can run a cleanup.
    atexit.register(stream.close)
    return stream
