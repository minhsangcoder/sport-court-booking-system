"""Peer resale system E2E: isolated SePay QR image fixture plus genuine BFF/Gateway/services/PostgreSQL/Rabbit.

No .env, real bank credentials, bank API or user service/database is read.
Only the initial synthetic Admin role is seeded; all business state uses APIs.
"""
import importlib.util
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('SPORTHUB_E2E_TEST_SCRIPT', 'peer-resale-system-e2e.mjs')
os.environ.setdefault('SPORTHUB_E2E_ARTIFACT_ROOT', str(ROOT / 'tmp/backend-peer-system-fixed'))
os.environ.setdefault('SPORTHUB_E2E_FRONTEND_ROOT', str(ROOT / 'frontend'))
os.environ.setdefault('SPORTHUB_E2E_OUTPUT', str(ROOT / 'tmp/real-system-evidence'))
spec = importlib.util.spec_from_file_location('real_system_harness', ROOT / 'scripts/system-e2e.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)
owned = []
provider = None
try:
    harness.PG = 'sporthub-real-test-pg-' + harness.RUN
    harness.REDIS = 'sporthub-real-test-redis-' + harness.RUN
    harness.PG_PORT, harness.REDIS_PORT = str(harness.free_port()), str(harness.free_port())
    harness.PG_USER, harness.PG_PASSWORD = 'real_test', 'synthetic_real_test_only'
    harness.command(['docker','run','-d','--name',harness.PG,'--label','sporthub.test=real-payments',
        '-p',f'127.0.0.1:{harness.PG_PORT}:5432','-e','POSTGRES_USER='+harness.PG_USER,
        '-e','POSTGRES_PASSWORD='+harness.PG_PASSWORD,'postgres:16-alpine'])
    owned.append(harness.PG)
    harness.command(['docker','run','-d','--name',harness.REDIS,'--label','sporthub.test=real-payments',
        '-p',f'127.0.0.1:{harness.REDIS_PORT}:6379','redis:7-alpine'])
    owned.append(harness.REDIS)
    for attempt in range(60):
        try:
            harness.command(['docker','exec',harness.PG,'pg_isready','-U',harness.PG_USER])
            break
        except Exception:
            time.sleep(.5)
    else:
        raise RuntimeError('Isolated PostgreSQL startup timeout')
    provider_port = harness.free_port()
    mock_origin = f'http://127.0.0.1:{provider_port}'
    os.environ['PAYOS_API_URL'] = mock_origin
    os.environ['BANK_CATALOG_URL'] = mock_origin + '/banks'
    with (harness.OUT/'native-provider.log').open('w', encoding='utf-8') as log:
        provider = subprocess.Popen([harness.NODE,str(ROOT/'scripts/test/payos-native-mock.mjs'),str(provider_port)],
            stdout=log,stderr=subprocess.STDOUT,creationflags=harness.NO_WINDOW)
        harness.wait_url(mock_origin+'/health', provider)
        raise SystemExit(harness.main())
finally:
    if provider is not None:
        provider.terminate()
        provider.wait(timeout=10)
    for name in owned:
        harness.command(['docker','stop','-t','3',name])
