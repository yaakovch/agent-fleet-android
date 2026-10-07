#!/usr/bin/env python3
"""Isolated real tmux/OpenSSH startup fixture; no accounts or AI requests.

Reserve SSH 9803 before start. Only the named fixture session is accepted.
The generated fixture.json contains an ephemeral private key: never publish it.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import time
import uuid


def run(*args: str, **kwargs: object) -> str:
    return subprocess.check_output(args, text=True, **kwargs).strip()


def environment(root: Path) -> dict[str, str]:
    state = json.loads((root / "state.json").read_text())
    return {**os.environ, "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"],
            "WTMUX_RUNTIME_BIN_DIR": str(root / "bin"),
            "WTMUX_SCHEDULER_TMUX_SOCKET": state["socket"],
            "WTMUX_CONVERSATION_STATE_DIR": str(root / "outcomes"),
            "WTMUX_CONFIG_PATH": str(root / "host.conf"), "CODEX_HOME": str(root / ".codex-fixture")}


def ssh(root: Path) -> None:
    state = json.loads((root / "state.json").read_text())
    outer = shlex.split(os.environ.get("SSH_ORIGINAL_COMMAND", ""))
    command = shlex.split(outer[2]) if len(outer) == 3 and outer[:2] == ["bash", "-lc"] else outer
    if command == ["python3", "-", "native-startup", state["session"]]:
        source = Path(state["source"]) / "lib/session_identity.py"
        payload = sys.stdin.buffer.read(64 * 1024 + 1)
        if payload != source.read_bytes():
            raise SystemExit("Only the verified read-only identity script is accepted")
        with (root / "requests.jsonl").open("a") as log:
            log.write(json.dumps({"at": time.time(), "channel": "identity", "session": state["session"]}) + "\n")
        os.execve(sys.executable, [sys.executable, str(source), "native-startup", state["session"]], environment(root))
    if (len(command) == 4 and command[:3] == ["python3", "-", state["session"]]
            and command[3].isdigit() and 100 <= int(command[3]) <= 5000):
        launcher = (Path(state["source"]) / "scripts/wtmux").read_text()
        marker = 'wtmux_exec_machine_command "$command_host" python3 - "$session_name" "$limit" <<\'PY\'\n'
        expected = (launcher.split(marker, 1)[1].split("\nPY\n", 1)[0] + "\n").encode()
        if sys.stdin.buffer.read(64 * 1024 + 1) != expected:
            raise SystemExit("Only the verified read-only scrollback script is accepted")
        source = root / "pane-scrollback-fixture.py"
        source.write_bytes(expected)
        source.chmod(0o600)
        with (root / "requests.jsonl").open("a") as log:
            log.write(json.dumps({"at": time.time(), "channel": "pane.scrollback", "session": state["session"]}) + "\n")
        os.execve(sys.executable, [sys.executable, str(source), state["session"], command[3]], environment(root))
    helper = str(Path(state["source"]) / "scripts/wtmux-host-runtime")
    if len(command) < 5 or command[0] != helper or command[2:4] != ["--machine", "native-startup"]:
        raise SystemExit("Invalid fixture runtime binding")
    rest = command[4:]
    valid = (command[1] == "terminal" and rest == ["attach", "--session", state["session"], "--exact"])
    valid |= command[1] == "control" and rest in (["--snapshot"], ["--stdio"])
    if command[1] == "conversation" and rest[:3] == ["stream", "--session", state["session"]]:
        tail = rest[3:]
        valid = all(value in {"--limit", "20", "--no-follow", "--view", "conversation", "detailed"} for value in tail)
    if not valid:
        raise SystemExit("Only read-only fixture streams and exact attachment are accepted")
    with (root / "requests.jsonl").open("a") as log:
        log.write(json.dumps({"at": time.time(), "channel": command[1], "session": state["session"]}) + "\n")
    if command[1] == "conversation":
        process = subprocess.Popen(command, env=environment(root), stdout=subprocess.PIPE, start_new_session=True)
        try:
            assert process.stdout is not None
            for line in process.stdout:
                frame = json.loads(line)
                with (root / "response-metadata.jsonl").open("a") as log:
                    log.write(json.dumps({"at": time.time(), "type": frame.get("type"),
                        "session": frame.get("session"), "items": len(frame.get("items", []))}) + "\n")
                sys.stdout.buffer.write(line)
                sys.stdout.buffer.flush()
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
            process.wait()
        raise SystemExit(process.returncode)
    os.execve(helper, command, environment(root))


def agent(root: Path) -> None:
    source = root / ".codex-fixture/sessions/rollout-native-startup.jsonl"
    with source.open("a", buffering=1) as stream:
        def message(role: str, text: str) -> None:
            stream.write(json.dumps({"type": "event_msg", "timestamp": "2026-10-07T00:00:00Z",
                                    "payload": {"type": "user_message" if role == "user" else "agent_message", "message": text}}) + "\n")
        message("user", "Startup fixture request")
        message("assistant", "NATIVE_STARTUP_READY: complete newest session content.")
        print("TERMINAL_STARTUP_READY", flush=True)
        for line in sys.stdin:
            value = line.strip()
            if value.startswith("native-input-") and len(value) < 80:
                print("INPUT_RECEIVED: " + value, flush=True)
                message("user", value)
                message("assistant", "INPUT_RECEIVED: " + value)


def start(args: argparse.Namespace, root: Path) -> None:
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    script = Path(__file__).resolve()
    state = {"source": str(args.source.resolve()), "session": "native-startup", "tmux": shutil.which("tmux"),
             "socket": "native-startup-" + uuid.uuid4().hex[:12], "processes": []}
    (root / "state.json").write_text(json.dumps(state))
    (root / "bin").mkdir()
    shim = root / "bin/tmux"
    shim.write_text('#!/bin/sh\nexec ' + shlex.quote(state["tmux"]) + ' -L ' + shlex.quote(state["socket"]) + ' "$@"\n')
    shim.chmod(0o700)
    (root / ".codex-fixture/sessions").mkdir(parents=True)
    (root / "host.conf").write_text("# wtmux shared-registry loader v2\nWTMUX_MACHINE_IDS=()\n")
    for name in ("ssh-host", "ssh-client"):
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(root / name))
    (root / "authorized_keys").write_text((root / "ssh-client.pub").read_text())
    (root / "authorized_keys").chmod(0o600)
    tmux = ["tmux", "-L", state["socket"]]
    run(*tmux, "new-session", "-d", "-s", state["session"], "-x", "130", "-y", "45", "-c", str(root),
        shlex.join([sys.executable, str(script), "agent", "--provider", "codex", "--root", str(root)]))
    for name, value in {"managed": "1", "tool": "codex", "project": "native-startup", "backend": "linux",
                        "display_name": "Native startup", "project_path": str(root), "execution_target_id": "linux"}.items():
        run(*tmux, "set-option", "-t", state["session"], "@wtmux_" + name, value)
    config = "\n".join(["Port 9803", "ListenAddress 127.0.0.1", f"HostKey {root}/ssh-host",
        f"AuthorizedKeysFile {root}/authorized_keys", f"PidFile {root}/sshd.pid", "PasswordAuthentication no",
        "KbdInteractiveAuthentication no", "UsePAM no", "PubkeyAuthentication yes", "StrictModes yes",
        "DisableForwarding yes", "PermitTTY yes", f"ForceCommand {sys.executable} {script} ssh --root {root}",
        "LogLevel ERROR", ""])
    (root / "sshd_config").write_text(config)
    with (root / "sshd.log").open("w") as log:
        process = subprocess.Popen(["/usr/sbin/sshd", "-D", "-e", "-f", str(root / "sshd_config")],
                                   stdout=log, stderr=log, start_new_session=True)
    state["processes"].append(process.pid)
    (root / "state.json").write_text(json.dumps(state))
    time.sleep(.2)
    if process.poll() is not None:
        raise RuntimeError((root / "sshd.log").read_text())
    fingerprint = run("ssh-keygen", "-lf", str(root / "ssh-host.pub")).split()[1]
    values = {"roles": "host", "platform": "linux", "linux_username": os.environ["USER"], "projects_root": str(root),
        "transport": "ssh", "fallback_ssh_host": args.address, "fallback_identity_file": "@KEY_PATH@",
        "endpoint_id": "native-startup-ssh", "endpoint_network": "direct", "endpoint_address": args.address,
        "endpoint_port": str(args.ssh_port), "endpoint_ssh_engine": "openssh", "endpoint_authentication": "ssh-key",
        "endpoint_identity_state": "verified", "endpoint_ssh_host_key_sha256": fingerprint,
        "host_command": str(args.source.resolve() / "scripts/wtmux-host")}
    client_config = "\n# wtmux shared-registry loader v2\nWTMUX_MACHINE_IDS+=(native-startup)\n"
    client_config += "\n".join(f"machine__native_startup__{key}={shlex.quote(value)}" for key, value in values.items()) + "\n"
    fixture = {"session": state["session"], "config": client_config, "privateKey": (root / "ssh-client").read_text(),
               "hostKey": (root / "ssh-host.pub").read_text(), "source": state["source"], "socket": state["socket"]}
    (root / "fixture.json").write_text(json.dumps(fixture))
    (root / "fixture.json").chmod(0o600)
    print(json.dumps({"root": str(root), "session": state["session"], "sshdPid": process.pid}))


def stop(root: Path) -> None:
    state = json.loads((root / "state.json").read_text())
    for pid in state["processes"]:
        if Path(f"/proc/{pid}/cmdline").exists() and str(root).encode() in Path(f"/proc/{pid}/cmdline").read_bytes():
            os.kill(pid, signal.SIGTERM)
    subprocess.run(["tmux", "-L", state["socket"], "kill-server"], check=False, capture_output=True)
    for name in ("authorized_keys", "ssh-client", "ssh-client.pub", "ssh-host", "ssh-host.pub", "fixture.json"):
        (root / name).unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["start", "ssh", "agent", "stop"])
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--address", default="127.0.0.1")
    parser.add_argument("--ssh-port", type=int, default=9804)
    parser.add_argument("--provider", choices=["codex"])
    options = parser.parse_args()
    if options.command == "start":
        if options.source is None:
            parser.error("start requires --source")
        start(options, options.root.resolve())
    else:
        {"ssh": ssh, "agent": agent, "stop": stop}[options.command](options.root.resolve())
