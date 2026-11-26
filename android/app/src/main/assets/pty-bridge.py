#!/usr/bin/env python3
import fcntl
import os
import pty
import select
import subprocess
import sys


def main():
  if len(sys.argv) < 2:
    sys.stderr.write("Usage: pty-bridge.py <command> [args...]\n")
    sys.exit(1)

  master, slave = pty.openpty()

  env = os.environ.copy()
  env["TERM"] = "xterm-256color"
  env.pop("ANTIGRAVITY_SAFECLIS_SOURCE", None)

  cmd = sys.argv[1:]

  proc = subprocess.Popen(
      cmd, stdin=slave, stdout=slave, stderr=slave, close_fds=True, env=env
  )
  os.close(slave)

  # Non-blocking I/O
  for fd in (master, sys.stdin.fileno()):
    try:
      fl = fcntl.fcntl(fd, fcntl.F_GETFL)
      fcntl.fcntl(fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)
    except Exception:
      pass

  while True:
    if proc.poll() is not None:
      # Drain remaining output from master
      try:
        while True:
          data = os.read(master, 2048)
          if not data:
            break
          sys.stdout.buffer.write(data)
          sys.stdout.buffer.flush()
      except OSError:
        pass
      break

    r, _, _ = select.select([sys.stdin, master], [], [], 0.1)

    if sys.stdin in r:
      try:
        data = os.read(sys.stdin.fileno(), 1024)
        if data:
          os.write(master, data)
      except OSError:
        pass

    if master in r:
      try:
        data = os.read(master, 2048)
        if data:
          sys.stdout.buffer.write(data)
          sys.stdout.buffer.flush()
      except OSError:
        pass

  sys.exit(proc.returncode if proc.returncode is not None else 1)


if __name__ == "__main__":
  main()
