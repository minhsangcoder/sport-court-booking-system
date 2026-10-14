"""Regression checks for host/container connection separation and secret handling."""
import contextlib
import copy
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import local


def compose_fixture():
    services = {name: {"environment": {
        "POSTGRES_USER": "test_role", "POSTGRES_PASSWORD": "test-only $password#value",
        "JWT_SECRET": "test-only quoted secret", "RABBITMQ_HOST": "rabbitmq",
        "REDIS_HOST": "redis", "FRONTEND_BASE_URL": "http://localhost:13000",
        "IDENTITY_DB_URL": "jdbc:postgresql://postgres:5432/identity_db",
    }} for name in local.SERVICES}
    for name, target, published in (("postgres", 5432, 15432), ("redis", 6379, 16379),
                                     ("rabbitmq", 5672, 15682), ("minio", 9000, 19000)):
        services[name] = {"ports": [{"target": target, "published": str(published)}]}
    return {"services": services}


class LocalLauncherTests(unittest.TestCase):
    def test_all_host_services_use_published_ports_and_preserve_credentials(self):
        config = compose_fixture()
        original = copy.deepcopy(config)
        with patch.dict(os.environ, {"SPRING_DATASOURCE_URL": "jdbc:postgresql://localhost:5432/wrong",
                                     "SPRING_FLYWAY_URL": "jdbc:postgresql://localhost:5432/wrong",
                                     "SPRING_DATA_REDIS_PORT": "6379"}, clear=True):
            for service in local.SERVICES:
                env = local.host_environment(config, service)
                self.assertNotIn("SPRING_DATASOURCE_URL", env)
                self.assertNotIn("SPRING_FLYWAY_URL", env)
                self.assertNotIn("SPRING_DATA_REDIS_PORT", env)
                self.assertEqual(env["POSTGRES_PASSWORD"], "test-only $password#value")
                self.assertEqual(env["REDIS_PORT"], "16379")
                self.assertEqual(env["RABBITMQ_PORT"], "15682")
                self.assertEqual(env["MINIO_ENDPOINT"], "http://127.0.0.1:19000")
                readiness = env["MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE"].split(",")
                self.assertIn("readinessState", readiness)
                self.assertNotIn("mail", readiness)
                if service != "api-gateway":
                    self.assertIn("db", readiness)
                    self.assertIn("rabbit", readiness)
                for database in ("identity", "facility", "schedule", "booking", "payment", "transfer"):
                    self.assertEqual(env[database.upper() + "_DB_URL"],
                                     f"jdbc:postgresql://127.0.0.1:15432/{database}_db")
        self.assertEqual(config, original)

    def test_command_failure_never_exposes_captured_output(self):
        result = subprocess.CompletedProcess([], 1, stdout="private secret", stderr="another secret")
        with patch.object(local.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "^Safe diagnostic$"):
                local.checked(["docker"], "Safe diagnostic")

    def test_java_secrets_are_environment_only_and_redacted_from_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "backend/identity-service/target/identity-service-0.0.1-SNAPSHOT.jar"
            jar.parent.mkdir(parents=True)
            jar.touch()
            child = unittest.mock.Mock()
            child.stdout = io.StringIO("test-only $password#value test-only quoted secret\nREADY\n")
            child.wait.return_value = 0
            child.poll.return_value = 0
            output = io.StringIO()
            with patch.object(local, "ROOT", root), patch.object(local.subprocess, "Popen", return_value=child) as popen, \
                    patch.object(local.signal, "signal"), contextlib.redirect_stdout(output):
                self.assertEqual(local.run_service(compose_fixture(), "identity-service"), 0)
            self.assertEqual(output.getvalue(), "[REDACTED] [REDACTED]\nREADY\n")
            self.assertNotIn("test-only", " ".join(popen.call_args.args[0]))
            self.assertEqual(popen.call_args.kwargs["env"]["POSTGRES_PASSWORD"], "test-only $password#value")

    def test_missing_middle_migration_is_rejected_even_when_latest_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            migrations = root / "backend/identity-service/src/main/resources/db/migration"
            migrations.mkdir(parents=True)
            for version in (1, 2, 3):
                (migrations / f"V{version}__example.sql").touch()
            with patch.object(local, "ROOT", root), patch.object(local, "checked", return_value="1|t|SQL\n3|t|SQL\n"):
                with self.assertRaisesRegex(RuntimeError, "history mismatch"):
                    local.verify_migrations()


if __name__ == "__main__":
    unittest.main()
