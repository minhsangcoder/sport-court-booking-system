"""Boot the checked-in jars and Next build against fresh, labelled test resources.

Requires an existing *test-labelled* PG and Redis, JDK21, Node and Playwright.
No .env is loaded. Databases/containers/evidence are retained; only this run's
processes and containers are stopped. See docs/final-system-verification.md.
"""
import base64
import json
import http.server
import shutil
import os
import re
import socket
import socketserver
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get('SPORTHUB_E2E_OUTPUT', str(Path(os.environ['TEMP']) / 'sporthub-system-phase12')))
OUT.mkdir(parents=True, exist_ok=True)
RUN = uuid.uuid4().hex[:10]
SERVICES = ['identity', 'facility', 'schedule', 'booking', 'payment', 'transfer']
PG = os.environ.get('SPORTHUB_E2E_PG_CONTAINER', 'sporthub-phase01-pg')
PG_PORT = os.environ.get('SPORTHUB_E2E_PG_PORT', '63479')
PG_USER = os.environ.get('SPORTHUB_E2E_PG_USER', 'phase01_test')
PG_PASSWORD = os.environ.get('SPORTHUB_E2E_PG_PASSWORD', 'phase01_synthetic_only')
REDIS = os.environ.get('SPORTHUB_E2E_REDIS_CONTAINER', 'sporthub-phase01-redis')
REDIS_PORT = os.environ.get('SPORTHUB_E2E_REDIS_PORT', '52386')
JAVA = os.environ.get('SPORTHUB_E2E_JAVA', 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe')
JAVA_HEAP_MB = int(os.environ.get('SPORTHUB_E2E_JAVA_HEAP_MB', '256'))
if not 128 <= JAVA_HEAP_MB <= 512:
    raise ValueError('Isolated service heap must be between 128 and 512 MiB')
NODE = os.environ['SPORTHUB_E2E_NODE']
TEST_SCRIPT = os.environ.get('SPORTHUB_E2E_TEST_SCRIPT', 'system-e2e.mjs')
if TEST_SCRIPT not in ['system-e2e.mjs', 'counter-booking-ownership-e2e.mjs', 'demo-finance-e2e.mjs', 'real-payments-e2e.mjs', 'peer-resale-system-e2e.mjs']:
    raise RuntimeError('Choose a checked-in system test harness')
if TEST_SCRIPT == 'real-payments-e2e.mjs':
    TEST_SCRIPT = 'peer-resale-system-e2e.mjs'
    os.environ['SPORTHUB_E2E_TEST_SCRIPT'] = TEST_SCRIPT
OWNERSHIP_TEST = TEST_SCRIPT == 'counter-booking-ownership-e2e.mjs'
DEMO_FINANCE_TEST = TEST_SCRIPT == 'demo-finance-e2e.mjs'
PEER_RESALE_TEST = TEST_SCRIPT == 'peer-resale-system-e2e.mjs'
REAL_PAYMENTS_TEST = TEST_SCRIPT == 'real-payments-e2e.mjs' or PEER_RESALE_TEST
ARTIFACT_ROOT = Path(os.environ.get('SPORTHUB_E2E_ARTIFACT_ROOT', str(ROOT / 'backend'))).resolve()
FRONTEND_ROOT = Path(os.environ.get('SPORTHUB_E2E_FRONTEND_ROOT', str(ROOT / 'frontend'))).resolve()
ARTIFACT_SUFFIX = os.environ.get('SPORTHUB_E2E_ARTIFACT_SUFFIX', '')
if not re.fullmatch(r'[-\w]*', ARTIFACT_SUFFIX):
    raise RuntimeError('Invalid test artifact suffix')
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
processes, streams, containers, codes = [], [], [], {}


def command(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, creationflags=NO_WINDOW).stdout.strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def rabbit_ready(port):
    """Require an actual AMQP connection.start, not just Erlang/TCP proxy health."""
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=2) as sock:
            sock.sendall(b'AMQP\x00\x00\x09\x01')
            header = b''
            while len(header) < 7:
                chunk = sock.recv(7 - len(header))
                if not chunk:
                    return False
                header += chunk
            if header[:3] != b'\x01\x00\x00':
                return False
            method = b''
            while len(method) < 4:
                chunk = sock.recv(4 - len(method))
                if not chunk:
                    return False
                method += chunk
            return method == b'\x00\x0a\x00\x0a'
    except OSError:
        return False


def wait_url(url, process=None, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process and process.poll() is not None:
            raise RuntimeError('Process exited before readiness: ' + url)
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(.5)
    raise RuntimeError('Readiness timeout: ' + url)


def spawn(args, env, name):
    log = (OUT / (name + '.log')).open('w', encoding='utf-8')
    streams.append(log)
    process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=NO_WINDOW)
    processes.append(process)
    return process


class SMTP(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b'220 loopback test SMTP\r\n')
        recipient = None
        while True:
            line = self.rfile.readline()
            if not line:
                return
            text = line.decode().strip()
            if text.upper().startswith('RCPT TO'):
                recipient = re.search(r'<([^>]+)>', text).group(1)
                synthetic_sms = (OWNERSHIP_TEST or DEMO_FINANCE_TEST or REAL_PAYMENTS_TEST) and re.fullmatch(r'849[0-9]{8}@sms\.sporthub\.local', recipient)
                if not recipient.endswith('@phase12.invalid') and not synthetic_sms:
                    self.wfile.write(b'550 Test recipients only\r\n')
                    continue
            if text.upper() == 'DATA':
                self.wfile.write(b'354 End with dot\r\n')
                body = []
                while True:
                    line = self.rfile.readline()
                    if line in (b'.\r\n', b''):
                        break
                    body.append(line)
                match = re.search(r'verification code is: ([0-9]{6})', b''.join(body).decode(errors='replace'))
                if match and recipient:
                    codes[recipient] = match.group(1)
                    # Synthetic OTP only; never a real mailbox or production secret.
                    (OUT / 'smtp-codes.json').write_text(json.dumps(codes), encoding='utf-8')
                self.wfile.write(b'250 Accepted in memory\r\n')
            elif text.upper() == 'QUIT':
                self.wfile.write(b'221 Bye\r\n')
                return
            else:
                self.wfile.write(b'250 localhost\r\n')


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    # Fail closed on an unrelated database/container. Never use the root demo stack.
    for name in [PG, REDIS]:
        labels = json.loads(command(['docker', 'inspect', '--format', '{{json .Config.Labels}}', name])) or {}
        if not any('phase' in k.lower() or 'test' in k.lower() for k in labels):
            raise RuntimeError('Refusing container without test/phase label: ' + name)
    smtp = socketserver.ThreadingTCPServer(('127.0.0.1', 0), SMTP)
    smtp.daemon_threads = True
    threading.Thread(target=smtp.serve_forever, daemon=True).start()
    ports = {name: free_port() for name in SERVICES + ['gateway', 'frontend', 'rabbit', 'minio']}
    dbs = {name: name + '_system_phase12_' + RUN for name in SERVICES}
    control = None
    try:
        for name in SERVICES:
            command(['docker', 'exec', PG, 'createdb', '-U', PG_USER, dbs[name]])
            command(['docker', 'exec', PG, 'psql', '-U', PG_USER, '-d', dbs[name], '-v', 'ON_ERROR_STOP=1', '-c',
                     'CREATE EXTENSION "uuid-ossp"; CREATE EXTENSION btree_gist;'])
        rabbit = 'sporthub-phase12-rabbit-' + RUN
        minio = 'sporthub-phase12-minio-' + RUN
        containers.append(rabbit)
        command(['docker', 'run', '-d', '--name', rabbit, '--label', 'sporthub.test.phase=12',
                 '-p', f'127.0.0.1:{ports["rabbit"]}:5672', '-e', 'RABBITMQ_DEFAULT_USER=phase12_test',
                 '-e', 'RABBITMQ_DEFAULT_PASS=phase12_synthetic_only', '-e', 'RABBITMQ_DEFAULT_VHOST=phase12',
                 'rabbitmq:3.13-management-alpine'])
        containers.append(minio)
        command(['docker', 'run', '-d', '--name', minio, '--label', 'sporthub.test.phase=12',
                 '-p', f'127.0.0.1:{ports["minio"]}:9000', '-e', 'MINIO_ROOT_USER=phase12_test',
                 '-e', 'MINIO_ROOT_PASSWORD=phase12_synthetic_only',
                 'ghcr.io/coollabsio/minio@sha256:a4938f37f1be1841b8e7b627ad0207b265345fd0d063e42d7410c78af0e63e68',
                 'server', '/data'])
        wait_url(f'http://127.0.0.1:{ports["minio"]}/minio/health/live')
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            result = subprocess.run(['docker', 'exec', rabbit, 'rabbitmq-diagnostics', '-q', 'ping'], capture_output=True, creationflags=NO_WINDOW)
            if result.returncode == 0:
                # Erlang ping/TCP proxy can succeed before AMQP is ready.
                if rabbit_ready(ports['rabbit']):
                    break
            time.sleep(1)
        else:
            diagnostic = subprocess.run(['docker', 'logs', '--tail', '100', rabbit], capture_output=True, text=True, encoding='utf-8', errors='replace', creationflags=NO_WINDOW)
            (OUT/'rabbit-startup.log').write_text(diagnostic.stdout + diagnostic.stderr + '\nCLI: ' + result.stderr.decode('utf-8', errors='replace'), encoding='utf-8')
            raise RuntimeError('Rabbit startup timeout')
        env = dict(os.environ)
        env.update({'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED':'true', 'SERVER_ADDRESS':'127.0.0.1',
                    'SPRING_DATASOURCE_USERNAME':PG_USER, 'SPRING_DATASOURCE_PASSWORD':PG_PASSWORD,
                    # Match the application's configured ceiling. A synthetic ceiling of four
                    # can deadlock existing nested permission RowMapper reads during UI fan-out.
                    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE':'10', 'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE':'1',
                    'SPRING_RABBITMQ_HOST':'127.0.0.1', 'SPRING_RABBITMQ_PORT':str(ports['rabbit']),
                    'SPRING_RABBITMQ_USERNAME':'phase12_test', 'SPRING_RABBITMQ_PASSWORD':'phase12_synthetic_only',
                    'SPRING_RABBITMQ_VIRTUAL_HOST':'phase12', 'DEMO_SEED_ENABLED':'false',
                    'JWT_SECRET':'phase12-synthetic-jwt-secret-over-thirty-two-characters',
                    'JWT_ACCESS_TOKEN_EXPIRATION':'600000' if DEMO_FINANCE_TEST else '45000', 'SERVICE_CALL_SECRET':'phase12-synthetic-service-secret-over-thirty-two-characters',
                    'OWNER_APPLICATION_DATA_KEY':base64.b64encode(os.urandom(32)).decode(),
                    'REDIS_HOST':'127.0.0.1', 'REDIS_PORT':REDIS_PORT, 'REDIS_PASSWORD':'', 'SPRING_DATA_REDIS_PASSWORD':'',
                    'SPORTHUB_MEDIA_ACCESS_KEY':'phase12_test', 'SPORTHUB_MEDIA_SECRET_KEY':'phase12_synthetic_only',
                    'SPORTHUB_MEDIA_ENDPOINT':f'http://127.0.0.1:{ports["minio"]}', 'SPORTHUB_MEDIA_BUCKET':'phase12-synthetic',
                    'MINIO_PUBLIC_ENDPOINT':f'http://127.0.0.1:{ports["minio"]}',
                    'SPRING_MAIL_HOST':'127.0.0.1', 'SPRING_MAIL_PORT':str(smtp.server_address[1]),
                    'SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH':'false', 'SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE':'false',
                    'FRONTEND_BASE_URL':f'http://127.0.0.1:{ports["frontend"]}', 'APP_PUBLIC_URL':f'http://127.0.0.1:{ports["frontend"]}',
                    'SECURE_COOKIE':'false', 'DEMO_SMS_ENABLED':'true' if OWNERSHIP_TEST or DEMO_FINANCE_TEST or REAL_PAYMENTS_TEST else 'false', 'GROUP_MIN_LEAD_SECONDS':'180',
                    'GROUP_PAYMENT_DEADLINE_SECONDS':'180', 'SPORTHUB_EVENTS_OUTBOX_DELAY_MS':'300',
                    'DEMO_PAYMENT_CALLBACK_SECRET':'phase12-demo-callback-secret-over-thirty-two-characters',
                    'CORS_ALLOWED_ORIGIN':f'http://127.0.0.1:{ports["frontend"]}',
                    'GATEWAY_RATE_LIMIT_REPLENISH_RATE':'200', 'GATEWAY_RATE_LIMIT_BURST_CAPACITY':'400'})
        if DEMO_FINANCE_TEST:
            env['TRANSFER_ACQUISITION_SECONDS'] = '45'
            env['TRANSFER_FINANCE_ENABLED'] = 'true'
        if REAL_PAYMENTS_TEST:
            env.update({'SPORTHUB_MONEY_MODE':'REAL', 'SPORTHUB_COLLECTION_PROVIDER':'SEPAY', 'SEPAY_COLLECTION_CREATE_ENABLED':'true', 'PAYOS_COLLECTION_CREATE_ENABLED':'false',
                        'PAYMENT_TEST_PROVIDER_ALLOWED':'true',
                        'PAYMENT_SECRET_KEYS':'synthetic:'+base64.b64encode(os.urandom(32)).decode(),
                        'PAYMENT_WEBHOOK_ORIGIN':f'http://127.0.0.1:{ports["gateway"]}',
                        'JWT_ACCESS_TOKEN_EXPIRATION':'600000', 'TRANSFER_ACQUISITION_SECONDS':'90'})
        if PEER_RESALE_TEST:
            control_token = uuid.uuid4().hex
            if not (ARTIFACT_ROOT/'.peer-fixture-clock').is_file():
                raise RuntimeError('Peer integration requires the checked-in test clock overlay in isolated artifacts')
            env.update({'SPORTHUB_PEER_RESALE_FACILITIES':'*', 'SPORTHUB_HOLD_PROTECTION_ENABLED':'true',
                        'SPORTHUB_BANK_PROFILE_ENFORCEMENT':'true',
                        'MINIO_ENDPOINT':f'http://127.0.0.1:{ports["minio"]}',
                        'MINIO_ACCESS_KEY':'phase12_test', 'MINIO_SECRET_KEY':'phase12_synthetic_only',
                        'MINIO_PEER_EVIDENCE_BUCKET':'phase12-peer-evidence',
                        'SEPAY_QR_IMAGE_URL':env['PAYOS_API_URL']+'/sepay',
                        'JWT_ACCESS_TOKEN_EXPIRATION':'1800000'})
        for name in SERVICES:
            env[name.upper() + '_SERVICE_URL'] = f'http://127.0.0.1:{ports[name]}'
        running, launches = {}, {}
        for name in SERVICES + ['gateway']:
            module = 'api-gateway' if name == 'gateway' else name + '-service'
            child = dict(env, SERVER_PORT=str(ports[name]), SPORTHUB_EVENTS_OUTBOX_ENABLED='true' if name in ['facility','booking','payment','transfer'] else 'false')
            child['SPRING_PROFILES_ACTIVE'] = 'local' if name == 'payment' and not REAL_PAYMENTS_TEST else 'default'
            if PEER_RESALE_TEST and name in ['booking','schedule','payment','transfer']:
                child.update(SPORTHUB_E2E_CLOCK_ENABLED='true', SPORTHUB_E2E_CLOCK_TOKEN=control_token)
            if name != 'gateway':
                child['SPRING_DATASOURCE_URL'] = f'jdbc:postgresql://127.0.0.1:{PG_PORT}/{dbs[name]}'
            args = [JAVA, f'-Xmx{JAVA_HEAP_MB}m', '-jar', str(ARTIFACT_ROOT / module / 'target' / (module+'-0.0.1-SNAPSHOT'+ARTIFACT_SUFFIX+'.jar'))]
            launches[name] = (args, child)
            running[name] = spawn(args, child, name)
        for name, process in running.items():
            wait_url(f'http://127.0.0.1:{ports[name]}/actuator/health/liveness', process)
            print('Ready:', name, flush=True)
        frontend_env = dict(env, GATEWAY_URL=f'http://127.0.0.1:{ports["gateway"]}', NEXT_TELEMETRY_DISABLED='1', PORT=str(ports['frontend']), HOSTNAME='127.0.0.1')
        standalone=FRONTEND_ROOT/'.next/standalone'
        entries = [entry for entry in standalone.rglob('server.js')
                   if 'node_modules' not in entry.relative_to(standalone).parts]
        if len(entries) != 1:
            raise RuntimeError('Expected one standalone application entry: '+str(entries))
        runtime = entries[0].parent
        shutil.copytree(FRONTEND_ROOT/'.next/static',runtime/'.next/static',dirs_exist_ok=True)
        shutil.copytree(FRONTEND_ROOT/'public',runtime/'public',dirs_exist_ok=True)
        frontend = spawn([NODE, str(runtime/'server.js')], frontend_env, 'frontend-runtime')
        wait_url(f'http://127.0.0.1:{ports["frontend"]}', frontend)
        config = {'run':RUN, 'ports':ports, 'databases':dbs, 'pg':PG, 'pgUser':PG_USER, 'output':str(OUT), 'baseURL':f'http://127.0.0.1:{ports["frontend"]}', 'pids':[p.pid for p in processes], 'containers':containers, 'payosMockURL':env.get('PAYOS_API_URL') if REAL_PAYMENTS_TEST else None}
        config['performanceOnly'] = os.environ.get('SPORTHUB_E2E_PERFORMANCE_ONLY') == 'true'
        config['javaHeapMb'] = JAVA_HEAP_MB
        config['demoCompatibility'] = DEMO_FINANCE_TEST and os.environ.get('SPORTHUB_E2E_DEMO_COMPATIBILITY') == 'true'
        comparison_root = os.environ.get('SPORTHUB_E2E_COMPARISON_FRONTEND_ROOT')
        if PEER_RESALE_TEST and comparison_root:
            comparison = Path(comparison_root).resolve()
            previous = [entry for entry in (comparison/'.next/standalone').rglob('server.js')
                        if 'node_modules' not in entry.relative_to(comparison/'.next/standalone').parts]
            if len(previous) != 1:
                raise RuntimeError('Expected one preserved production comparison application')
            comparison_runtime = previous[0].parent
            shutil.copytree(comparison/'.next/static',comparison_runtime/'.next/static',dirs_exist_ok=True)
            shutil.copytree(comparison/'public',comparison_runtime/'public',dirs_exist_ok=True)
            comparison_port = free_port()
            previous_process = spawn([NODE,str(previous[0])],dict(frontend_env,PORT=str(comparison_port)),'frontend-comparison')
            config['comparisonBaseURL'] = f'http://127.0.0.1:{comparison_port}'
            wait_url(config['comparisonBaseURL'], previous_process)
            config['pids'].append(previous_process.pid)
        if REAL_PAYMENTS_TEST:
            if not PEER_RESALE_TEST:
                control_token = uuid.uuid4().hex

            class FaultControl(http.server.BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def do_POST(self):
                    if self.headers.get('Authorization') != 'Bearer ' + control_token or self.path not in ['/clock','/restart-payment','/restart-booking','/restart-transfer']:
                        self.send_error(403)
                        return
                    try:
                        # Only the process and broker created by this harness can be affected.
                        if self.path == '/clock':
                            if not PEER_RESALE_TEST:
                                self.send_error(404)
                                return
                            length = int(self.headers.get('Content-Length','0'))
                            if length < 1 or length > 200:
                                self.send_error(400)
                                return
                            body = self.rfile.read(length)
                            for target in ['booking','schedule','payment','transfer']:
                                advance = urllib.request.Request(f'http://127.0.0.1:{ports[target]}/__fixture/clock', data=body,
                                    headers={'Content-Type':'application/json','X-Fixture-Token':control_token}, method='POST')
                                with urllib.request.urlopen(advance,timeout=10) as result:
                                    result.read()
                            payload = b'{"advanced":true}'
                        else:
                            target = self.path.removeprefix('/restart-')
                            running[target].kill()
                            running[target].wait(timeout=10)
                            if command(['docker','inspect','--format','{{.State.Paused}}',rabbit]) == 'true':
                                command(['docker', 'unpause', rabbit])
                            args, child = launches[target]
                            running[target] = spawn(args, child, target+'-after-crash')
                            wait_url(f'http://127.0.0.1:{ports[target]}/actuator/health/liveness', running[target])
                            payload = b'{"restarted":true}'
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(payload)))
                        self.end_headers()
                        self.wfile.write(payload)
                    except Exception:
                        self.send_error(500, 'Synthetic restart failed; inspect fixture logs')

            control = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FaultControl)
            threading.Thread(target=control.serve_forever, daemon=True).start()
            config.update(faultControlURL=f'http://127.0.0.1:{control.server_address[1]}', faultControlToken=control_token)
            config['fixtureClockEnabled'] = PEER_RESALE_TEST
        (OUT/'runtime.json').write_text(json.dumps(config, indent=2), encoding='utf-8')
        print('Ready: frontend; genuine BFF/Gateway integration', flush=True)
        with (OUT/'browser-api.log').open('w', encoding='utf-8') as log:
            result = subprocess.run([NODE, str(ROOT/'frontend/tests'/TEST_SCRIPT), str(OUT/'runtime.json')], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=NO_WINDOW)
        print((OUT/'browser-api.log').read_text(encoding='utf-8'), flush=True)
        return result.returncode
    finally:
        if control is not None:
            control.shutdown()
            control.server_close()
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for stream in streams:
            stream.close()
        smtp.shutdown()
        smtp.server_close()
        for name in containers:
            subprocess.run(['docker', 'unpause', name], capture_output=True, creationflags=NO_WINDOW)
            subprocess.run(['docker', 'stop', '-t', '5', name], capture_output=True, creationflags=NO_WINDOW)
        print('Own processes stopped; test data/evidence retained.', flush=True)


if __name__ == '__main__':
    sys.exit(main())
