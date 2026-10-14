#!/usr/bin/env python3
"""Run host JVMs against this checkout's Docker infrastructure (Python 3.9+)."""
import argparse
import csv
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
SERVICES = {f"{name}-service": 18081 + index for index, name in enumerate(
    ("identity", "facility", "schedule", "booking", "payment", "transfer"))}
SERVICES["api-gateway"] = 18080
INFRASTRUCTURE = ("postgres", "redis", "rabbitmq", "minio")
COMPOSE = ["docker", "compose", "--project-directory", str(ROOT),
           "--env-file", str(ROOT / ".env"), "-f", str(ROOT / "docker-compose.yml")]


def checked(command, message, **kwargs):
    # Capture output: Compose config and database errors can contain credentials.
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(message)
    return result.stdout


def configuration():
    if not (ROOT / ".env").is_file():
        raise RuntimeError("Create .env from .env.example and replace its markers first.")
    raw = checked(COMPOSE + ["--profile", "apps", "config", "--format", "json"],
                  "Compose configuration failed; check .env and Docker Compose installation.")
    config = json.loads(raw)
    for service in SERVICES:
        for key, value in config["services"][service]["environment"].items():
            if value and "__REPLACE_" in str(value):
                raise RuntimeError(f"Replace the marker for {key} in .env.")
    return config


def published_port(config, service, target):
    for port in config["services"][service].get("ports", []):
        if int(port["target"]) == target:
            return str(port["published"])
    raise RuntimeError(f"No host port configured for {service}:{target}.")


def host_environment(config, service):
    env = os.environ.copy()
    # A stale IDE/shell override must not redirect this local launcher elsewhere.
    for key in list(env):
        if key.startswith(("SPRING_DATASOURCE_", "SPRING_FLYWAY_")):
            del env[key]
    env.update({key: str(value) for key, value in
                config["services"][service]["environment"].items() if value is not None})
    # Tool/shell DEBUG flags are unrelated to the application debug setting.
    env.pop("DEBUG", None)
    env["SERVER_PORT"] = str(SERVICES[service])
    dependencies = ["readinessState"]
    if service != "api-gateway":
        dependencies.extend(("db", "rabbit"))
    if service not in ("payment-service", "transfer-service"):
        dependencies.append("redis")
    env["MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED"] = "true"
    env["MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE"] = ",".join(dependencies)
    for name, port in SERVICES.items():
        if name != "api-gateway":
            env[name.replace("-", "_").upper() + "_URL"] = f"http://127.0.0.1:{port}"
            database = name.removesuffix("-service")
            env[database.upper() + "_DB_URL"] = (
                f"jdbc:postgresql://127.0.0.1:{published_port(config, 'postgres', 5432)}/{database}_db")
    env["REDIS_HOST"] = "127.0.0.1"
    env["REDIS_PORT"] = published_port(config, "redis", 6379)
    env["RABBITMQ_HOST"] = "127.0.0.1"
    env["RABBITMQ_PORT"] = published_port(config, "rabbitmq", 5672)
    env["MINIO_ENDPOINT"] = f"http://127.0.0.1:{published_port(config, 'minio', 9000)}"
    env["FRONTEND_BASE_URL"] = env.get("FRONTEND_BASE_URL", "http://localhost:13000")
    env["DEMO_PAYMENT_CALLBACK_URL"] = (
        "http://127.0.0.1:18085/api/v1/payments/providers/demo/callback")
    # Spring's relaxed binding would otherwise prefer stale exported overrides.
    for key in list(env):
        if key.startswith(("SPRING_DATA_REDIS_", "SPRING_RABBITMQ_")):
            del env[key]
    return env


def run_service(config, service):
    jar = ROOT / f"backend/{service}/target/{service}-0.0.1-SNAPSHOT.jar"
    if not jar.is_file():
        raise RuntimeError("Build first: mvn -f backend/pom.xml package -DskipTests")
    env = host_environment(config, service)
    secrets = sorted({value for key, value in env.items() if value and
                      re.search(r"PASSWORD|SECRET|_PASS$|_DATA_KEY$", key)}, key=len, reverse=True)
    process = subprocess.Popen(["java", "-Xmx256m", "-jar", str(jar)], cwd=ROOT, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    def forward(signum, _frame):
        if process.poll() is None:
            process.send_signal(signum)
    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    try:
        for line in process.stdout:
            for secret in secrets:
                line = line.replace(secret, "[REDACTED]")
            print(line, end="", flush=True)
        return process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait()


def healthy(port):
    try:
        with urlopen(f"http://127.0.0.1:{port}/actuator/health/readiness", timeout=2) as response:
            return json.load(response).get("status") == "UP"
    except (URLError, OSError, ValueError):
        return False


def process_alive(pid):
    if os.name == "nt":
        # os.kill(pid, 0) terminates processes on Windows rather than probing them.
        raw = checked(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                      "Cannot inspect running local services.")
        return any(len(row) > 1 and row[1] == str(pid) for row in csv.reader(raw.splitlines()))
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def prepare_databases():
    checked(COMPOSE + ["exec", "-T", "postgres", "sh", "-c",
            'export PGPASSWORD="$POSTGRES_PASSWORD"; '
            'psql -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -tAc "SELECT 1"'],
            "PostgreSQL authentication failed. Existing volumes retain their original role/password; "
            "restore matching .env credentials before retrying.")
    checked(COMPOSE + ["exec", "-T", "postgres", "sh",
                       "/docker-entrypoint-initdb.d/01-init-databases.sh"],
            "Database initialization failed; check the PostgreSQL role and volume permissions.")
    print("READY PostgreSQL: six databases available; existing data preserved.")


def verify_migrations():
    for service in list(SERVICES)[:-1]:
        database = service.removesuffix("-service")
        expected = {str(int(re.match(r"V(\d+)__", path.name)[1])) for path in
                    (ROOT / f"backend/{service}/src/main/resources/db/migration").glob("V*__*.sql")}
        sql = "SELECT version, success, type FROM flyway_schema_history ORDER BY installed_rank"
        raw = checked(COMPOSE + ["exec", "-T", "postgres", "sh", "-c",
                      'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At -c "$2"',
                      "sh", database + "_db", sql],
                      f"Cannot read Flyway history for {service}; start the service first.")
        rows = [line.split("|") for line in raw.splitlines() if line]
        if any(row[1] != "t" or row[2] != "SQL" for row in rows) or {r[0] for r in rows} != expected:
            raise RuntimeError(f"Flyway history mismatch in {service} (missing/failed/baselined migration).")
        print(f"PASS {service}: {len(expected)} migrations, V{max(map(int, expected))}, no failures.")


def start(config, skip_build, skip_infrastructure):
    if not skip_build:
        checked(["mvn", "-f", str(ROOT / "backend/pom.xml"), "package", "-DskipTests"],
                "Backend build failed; run mvn -f backend/pom.xml package -DskipTests for diagnostics.")
        print("PASS backend build.")
    if not skip_infrastructure:
        checked(COMPOSE + ["up", "-d", "--wait", "--wait-timeout", "120", *INFRASTRUCTURE],
                "Infrastructure startup failed; check Docker and published port availability.")
    actual = checked(COMPOSE + ["port", "postgres", "5432"], "PostgreSQL is not running.").strip()
    if actual != f"127.0.0.1:{published_port(config, 'postgres', 5432)}":
        raise RuntimeError("PostgreSQL published port differs from .env; rerun without --skip-infrastructure.")
    prepare_databases()
    logs = ROOT / "tmp/demo"
    logs.mkdir(parents=True, exist_ok=True)
    for service, port in SERVICES.items():
        pid_file = logs / f"{service}.pid"
        if pid_file.exists():
            try:
                if process_alive(int(pid_file.read_text().strip())) and healthy(port):
                    print(f"READY {service} (already running; restart after changing .env/JARs).")
                    continue
            except (OSError, ValueError):
                pass
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                raise RuntimeError(f"Port {port} is occupied; stop the existing {service} before retrying.")
        log_path = logs / f"{service}.log"
        fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.chmod(log_path, 0o600)
        with os.fdopen(fd, "w") as output:
            process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "run-service", service],
                                       cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        pid_file.write_text(str(process.pid))
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"{service} exited; inspect tmp/demo/{service}.log.")
            if healthy(port):
                print(f"READY {service}: http://127.0.0.1:{port}", flush=True)
                break
            time.sleep(0.5)
        else:
            process.terminate()
            raise RuntimeError(f"{service} did not become healthy; inspect tmp/demo/{service}.log.")
    verify_migrations()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    launch = commands.add_parser("start", help="Build and start all host JVMs plus Docker infrastructure")
    launch.add_argument("--skip-build", action="store_true")
    launch.add_argument("--skip-infrastructure", action="store_true")
    run = commands.add_parser("run-service", help="Run one host JVM, loading .env through Compose")
    run.add_argument("service", choices=SERVICES)
    commands.add_parser("verify-migrations", help="Verify every service's applied migration history")
    args = parser.parse_args()
    try:
        config = configuration()
        if args.command == "run-service":
            return run_service(config, args.service)
        if args.command == "start":
            start(config, args.skip_build, args.skip_infrastructure)
        else:
            verify_migrations()
        return 0
    except (RuntimeError, OSError, ValueError) as error:
        # Do not print command output, environment values, or raw exception details.
        print(f"ERROR: {error}" if isinstance(error, RuntimeError) else
              "ERROR: Local launcher failed; check tools, configuration and file permissions.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
