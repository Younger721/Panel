#!/usr/bin/env python3
"""Minimal paramiko SSH exec helper for the lab server.

Usage:
    python remote_exec.py "command string"
    python remote_exec.py -f cmdlist.txt     # one command per line

Read-only by design at this stage: nothing is written to the remote host
unless the caller explicitly passes a mutating command.
"""
import sys
import os
import socket

import paramiko

HOST = os.environ.get("LAB_HOST", "134.175.207.249")
PORT = int(os.environ.get("LAB_PORT", "22"))
USER = os.environ.get("LAB_USER", "ubuntu")
PASS = os.environ.get("LAB_PASS", "Hy040605")
TIMEOUT = 20


def connect():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(
        hostname=HOST,
        port=PORT,
        username=USER,
        password=PASS,
        timeout=TIMEOUT,
        banner_timeout=TIMEOUT,
        auth_timeout=TIMEOUT,
        look_for_keys=False,
        allow_agent=False,
    )
    return client


def run(client, cmd, timeout=60):
    stdin, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=False)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    rc = stdout.channel.recv_exit_status()
    return rc, out, err


def main():
    if len(sys.argv) < 2:
        print("usage: remote_exec.py <command> | -f file")
        return 2
    if sys.argv[1] == "-f":
        with open(sys.argv[2], "r", encoding="utf-8") as fh:
            cmds = [ln.rstrip("\n") for ln in fh if ln.strip() and not ln.startswith("#")]
    else:
        cmds = [" ".join(sys.argv[1:])]

    try:
        client = connect()
    except Exception as exc:  # noqa: BLE001
        print(f"CONNECT_FAIL {type(exc).__name__}: {exc}")
        return 1

    try:
        for cmd in cmds:
            rc, out, err = run(client, cmd)
            print(f"\n===== $ {cmd}\n[rc={rc}]")
            if out.strip():
                print(out.rstrip())
            if err.strip():
                print("[stderr] " + err.rstrip())
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
