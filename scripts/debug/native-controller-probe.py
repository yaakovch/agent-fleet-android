#!/usr/bin/env python3
"""Isolated Codex + pinned SSH fixture for NativeQuestionControllerTest.

Reserve HTTP 9802 and SSH 9803 in ports_list before start. No real account/API
credentials are used. Push fixture.json only to the guarded API 36 emulator;
run the focused instrumentation, then verify and stop. Never use a phone.
"""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import time
import tempfile


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=kwargs.pop('timeout', 30), **kwargs).stdout


def serve(root):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            outputs = [x for x in request.get('input', []) if x.get('type') == 'function_call_output']
            valid = False
            if outputs:
                matching = [x for x in outputs if x.get('call_id') == 'call_fixture']
                expected = {'answers': {f'q{i}': {'answers': ['Second']} for i in range(3)}}
                valid = len(matching) == 1 and json.loads(matching[0]['output']) == expected
                if not valid:
                    self.send_error(422, 'Selected answers did not match the fixture')
                    return
            with (root / 'provider.jsonl').open('a') as log:
                log.write(json.dumps({'at': time.time(), 'outputs': outputs, 'validated': valid}) + '\n')
            questions = [{'id': f'q{i}', 'header': 'Setting', 'question': f'Choose fixture setting {i}',
                          'options': [{'label': 'First', 'description': 'First option'},
                                      {'label': 'Second', 'description': 'Second option'}]} for i in range(3)]
            item = ({'type': 'message', 'id': 'msg_done', 'role': 'assistant', 'phase': 'final_answer',
                     'content': [{'type': 'output_text', 'text': 'ANSWERS_RECEIVED: Codex continued.', 'annotations': []}]}
                    if valid else {'type': 'function_call', 'id': 'fc_fixture', 'call_id': 'call_fixture',
                                   'name': 'request_user_input', 'arguments': json.dumps({'questions': questions})})
            events = [
                {'type': 'response.created', 'response': {'id': 'resp_fixture', 'status': 'in_progress'}},
                {'type': 'response.output_item.added', 'output_index': 0, 'item': item},
                {'type': 'response.output_item.done', 'output_index': 0, 'item': item},
                {'type': 'response.completed', 'response': {'id': 'resp_fixture', 'status': 'completed', 'output': [item],
                 'usage': {'input_tokens': 10, 'output_tokens': 10, 'total_tokens': 20}}},
            ]
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Connection', 'close')
            self.end_headers()
            for event in events:
                self.wfile.write(('event: ' + event['type'] + '\ndata: ' + json.dumps(event) + '\n\n').encode())
                self.wfile.flush()
    ThreadingHTTPServer(('127.0.0.1', 9802), Handler).serve_forever()


def environment(root):
    return dict(os.environ, HOME=str(root), PATH=str(Path.home() / '.local/bin') + os.pathsep + os.environ['PATH'],
                WTMUX_SCHEDULER_TMUX_SOCKET='agent-fleet-controller-probe',
                WTMUX_CONVERSATION_STATE_DIR=str(root / 'outcomes'),
                WTMUX_CONFIG_PATH=str(root / 'host.conf'))


def ssh_command(root):
    state = json.loads((root / 'state.json').read_text())
    command = shlex.split(os.environ.get('SSH_ORIGINAL_COMMAND', ''))
    if len(command) != 3 or command[:2] != ['bash', '-lc']:
        raise SystemExit('Only the fixture runtime command is accepted')
    args = shlex.split(command[2])
    expected = [str(Path(state['source']) / 'scripts/wtmux-host-runtime'), 'conversation', '--machine', 'controller-probe']
    if (args[:4] != expected or len(args) < 7 or args[4] not in ('stream', 'answer')
            or args[5:7] != ['--session', state['session']]):
        raise SystemExit('Only the isolated fixture session is accepted')
    with (root / 'ssh-requests.jsonl').open('a') as log:
        log.write(json.dumps({'at': time.time(), 'args': args}) + '\n')
    if args[4] == 'stream':
        os.execve(args[0], args, environment(root))
    result = subprocess.run(args, env=environment(root), capture_output=True, text=True, timeout=100)
    with (root / 'answer-results.jsonl').open('a') as log:
        log.write(json.dumps({'at': time.time(), 'code': result.returncode,
                             'stdout': result.stdout, 'stderr': result.stderr}) + '\n')
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    raise SystemExit(result.returncode)


def start(args, root):
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    source = args.source.resolve()
    state = {'source': str(source), 'session': 'controller-question', 'processes': []}
    (root / 'state.json').write_text(json.dumps(state))
    (root / 'host.conf').write_text('# wtmux shared-registry loader v2\nWTMUX_MACHINE_IDS=()\n')
    for name in ('ssh-host', 'ssh-client'):
        run('ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(root / name))
    auth_base = Path.home() / '.cache/agent-fleet'
    auth_base.mkdir(parents=True, exist_ok=True)
    auth_dir = Path(tempfile.mkdtemp(prefix='controller-probe-', dir=auth_base))
    auth_file = auth_dir / 'authorized_keys'
    auth_file.write_text((root / 'ssh-client.pub').read_text())
    auth_file.chmod(0o600)
    state['authorizedKeys'] = str(auth_file)
    (root / 'state.json').write_text(json.dumps(state))
    script = Path(__file__).resolve()
    config = '\n'.join([
        'Port 9803', f'ListenAddress {args.listen_address}', f'HostKey {root}/ssh-host',
        f'AuthorizedKeysFile {auth_file}', f'PidFile {root}/sshd.pid',
        'PasswordAuthentication no', 'KbdInteractiveAuthentication no', 'UsePAM no',
        'PubkeyAuthentication yes', 'StrictModes yes', 'DisableForwarding yes',
        'PermitTTY no', f'ForceCommand {sys.executable} {script} ssh --root {root}',
        'LogLevel ERROR', '',
    ])
    (root / 'sshd_config').write_text(config)
    for name, command in [('provider', [sys.executable, str(script), 'serve', '--root', str(root)]),
                          ('sshd', ['/usr/sbin/sshd', '-D', '-e', '-f', str(root / 'sshd_config')])]:
        with (root / f'{name}.log').open('w') as log:
            process = subprocess.Popen(command, stdout=log, stderr=log, start_new_session=True)
        state['processes'].append(process.pid)
        (root / 'state.json').write_text(json.dumps(state))
        time.sleep(.2)
        if process.poll() is not None:
            raise RuntimeError(f'{name} failed: {(root / (name + ".log")).read_text()}')
    print(json.dumps(state))


def prepare(args, root):
    state = json.loads((root / 'state.json').read_text())
    codex_home = root / '.codex-home'
    codex_home.mkdir(exist_ok=True)
    shutil.copyfile(args.models_cache, codex_home / 'models_cache.json')
    (codex_home / 'config.toml').write_text('''model = "gpt-6-astra"
model_provider = "fixture"
model_reasoning_effort = "medium"
[model_providers.fixture]
name = "Native controller fixture"
base_url = "http://127.0.0.1:9802/v1"
wire_api = "responses"
requires_openai_auth = false
supports_websockets = false
''' + f'[projects."{root}"]\ntrust_level = "trusted"\n')
    tmux = ['tmux', '-L', 'agent-fleet-controller-probe']
    run(*tmux, 'new-session', '-d', '-s', state['session'], '-x', '130', '-y', '45', '-c', str(root),
        shlex.join(['env', f'CODEX_HOME={codex_home}', str(Path.home() / '.local/bin/codex'),
                    '--no-alt-screen', '-a', 'never', '-s', 'read-only']))
    run(*tmux, 'set-option', '-t', state['session'], '@wtmux_tool', 'codex')
    def wait_for(marker):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            text = run(*tmux, 'capture-pane', '-p', '-t', state['session'])
            if marker in text:
                return
            time.sleep(.2)
        raise RuntimeError(f'Codex did not reach {marker}: {text}')
    def send(text):
        run(*tmux, 'send-keys', '-t', state['session'], '-l', text)
        time.sleep(.5)
        run(*tmux, 'send-keys', '-t', state['session'], 'Enter')
    wait_for('Ask Codex')
    time.sleep(3)
    send('/plan')
    wait_for('Plan mode')
    send('Ask the fixture questions now')
    wait_for('Question 1/3')
    snapshot = json.loads(run(str(Path(state['source']) / 'scripts/wtmux-conversation'), 'stream',
                              '--session', state['session'], '--no-follow', env=environment(root)).splitlines()[0])
    (root / 'initial-snapshot.json').write_text(json.dumps(snapshot))
    question = next(x for x in snapshot['items'] if x['kind'] == 'question' and x['state'] == 'pending')
    fingerprint = run('ssh-keygen', '-lf', str(root / 'ssh-host.pub')).split()[1]
    values = {'roles': 'host', 'platform': 'linux', 'linux_username': os.environ['USER'],
              'projects_root': str(root), 'transport': 'ssh', 'fallback_ssh_host': args.address,
              'fallback_identity_file': '@KEY_PATH@', 'endpoint_id': 'controller-probe-ssh',
              'endpoint_network': 'direct', 'endpoint_address': args.address, 'endpoint_port': str(args.ssh_port),
              'endpoint_ssh_engine': 'openssh', 'endpoint_authentication': 'ssh-key',
              'endpoint_identity_state': 'verified', 'endpoint_ssh_host_key_sha256': fingerprint,
              'host_command': str(Path(state['source']) / 'scripts/wtmux-host')}
    config = '\n# wtmux shared-registry loader v2\nWTMUX_MACHINE_IDS+=(controller-probe)\n'
    config += '\n'.join(f'machine__controller_probe__{key}={shlex.quote(value)}' for key, value in values.items()) + '\n'
    fixture = {'session': state['session'], 'config': config, 'privateKey': (root / 'ssh-client').read_text(),
               'questions': [{'id': q['id'], 'secondOption': q['options'][1]['id']} for q in question['questions']]}
    (root / 'fixture.json').write_text(json.dumps(fixture))
    (root / 'fixture.json').chmod(0o600)
    print(str(root / 'fixture.json'))


def verify(root):
    state = json.loads((root / 'state.json').read_text())
    calls = [json.loads(x) for x in (root / 'ssh-requests.jsonl').read_text().splitlines()]
    answers = [x for x in calls if x['args'][4] == 'answer']
    results = [json.loads(x) for x in (root / 'answer-results.jsonl').read_text().splitlines()]
    deliveries = [json.loads(x) for x in (root / 'provider.jsonl').read_text().splitlines() if json.loads(x)['validated']]
    assert len(answers) == len(results) == len(deliveries) == 1, 'Expected exactly one answer and provider delivery'
    result = results[0]
    assert result['code'] == 0, result
    receipt = json.loads(result['stdout'])
    snapshot = json.loads((root / 'initial-snapshot.json').read_text())
    question = next(x for x in snapshot['items'] if x['kind'] == 'question' and x['state'] == 'pending')
    assert (receipt['protocolVersion'] == 2 and receipt['type'] == 'question.response'
            and receipt['session'] == state['session'] and receipt['status'] == 'delivered'
            and receipt['questionId'] == question['id']), receipt
    args = answers[0]['args']
    payload = args[args.index('--answers-b64') + 1]
    payload = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
    assert len(payload['answers']) == 3
    output = {'receipt': receipt, 'answers': payload, 'providerValidated': True, 'submissions': 1,
              'eventPosition': args[args.index('--event-position') + 1],
              'submittedAt': answers[0]['at'], 'providerReceivedAt': deliveries[0]['at'],
              'receiptAt': result['at'], 'verifiedAt': time.time()}
    (root / 'verified.json').write_text(json.dumps(output, indent=2))
    print(json.dumps(output))


def stop(root):
    state = json.loads((root / 'state.json').read_text())
    subprocess.run(['tmux', '-L', 'agent-fleet-controller-probe', 'kill-session', '-t', state['session']],
                   capture_output=True, timeout=30)
    for pid in state['processes']:
        # Verify ownership before signaling a PID that may have been recycled.
        cmdline = Path(f'/proc/{pid}/cmdline')
        if cmdline.exists() and str(root).encode() in cmdline.read_bytes():
            os.killpg(pid, signal.SIGTERM)
    if state.get('authorizedKeys'):
        auth_file = Path(state['authorizedKeys'])
        auth_file.unlink(missing_ok=True)
        auth_file.parent.rmdir()
    for name in ('ssh-client', 'authorized_keys', 'fixture.json'):
        (root / name).unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start', 'prepare', 'serve', 'ssh', 'verify', 'stop'])
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--models-cache', type=Path)
    parser.add_argument('--address', default='10.0.2.2', help='Address seen by the guarded emulator')
    parser.add_argument('--listen-address', default='127.0.0.1')
    parser.add_argument('--ssh-port', type=int, default=9803, help='SSH port seen by the guarded emulator')
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command in ('start', 'prepare'):
        globals()[args.command](args, root)
    else:
        globals()[{'ssh': 'ssh_command'}.get(args.command, args.command)](root)
