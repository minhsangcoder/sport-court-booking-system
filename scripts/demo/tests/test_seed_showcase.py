"""Showcase regressions; no running services or Docker are required."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import date
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[1] / "seed-showcase.py"
SPEC = importlib.util.spec_from_file_location("seed_showcase", SCRIPT)
showcase = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(showcase)
ORIGINAL_OPEN = Path.open


def cp1252_open(path, mode="r", buffering=-1, encoding=None, errors=None, newline=None):
    """Emulate a Windows default code page for unspecified text encodings."""
    if "b" not in mode and encoding is None:
        encoding = "cp1252"
    return ORIGINAL_OPEN(path, mode, buffering, encoding, errors, newline)


class FixtureApi:
    base = "http://localhost:18080/api/v1"

    def get(self, path):
        if path != "/sport-categories":
            raise AssertionError("Unexpected service request: " + path)
        return []


class ShowcasePortabilityTest(unittest.TestCase):
    def test_help_without_an_iana_timezone_database(self):
        result = subprocess.run(
            [sys.executable, "-S", str(SCRIPT), "--help"],
            env={**os.environ, "PYTHONTZPATH": ""},
            capture_output=True, text=True, encoding="utf-8",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--verify-only", result.stdout)

    def test_vietnamese_files_round_trip_with_a_windows_default_encoding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "showcase-manifest.json"
            (root / ".env").write_text("APP_PUBLIC_URL=http://localhost:13000\nDEMO_PASSWORD=MậtKhẩu\n", encoding="utf-8")
            with patch.object(Path, "open", cp1252_open):
                with patch.object(showcase, "ROOT", root):
                    self.assertEqual(showcase.load_env()["DEMO_PASSWORD"], "MậtKhẩu")
                instance = showcase.Showcase(FixtureApi(), manifest, date(2026, 10, 11))
                instance.data.update(note="Đà Nẵng", verifiedAt="2026-10-11T18:00:00+07:00")
                instance.save()
                with patch.object(showcase, "ROOT", root):
                    instance.report({"note": "Đà Nẵng"})
                restored = showcase.Showcase(FixtureApi(), manifest, None)
            self.assertEqual(restored.data["note"], "Đà Nẵng")
            self.assertEqual(restored.provinces[0]["name"], "Hà Nội")
            self.assertEqual(json.loads(manifest.read_text(encoding="utf-8"))["note"], "Đà Nẵng")
            self.assertIn("Đà Nẵng", (root / "showcase-summary.md").read_text(encoding="utf-8"))

    def test_customer_fixture_uses_utf8_for_the_sql_pipe(self):
        with patch.object(Path, "open", cp1252_open), patch.object(
            showcase.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")
        ) as run:
            showcase.seed_customers()
        self.assertIn("Nguyễn Minh Anh", run.call_args.kwargs["input"])
        self.assertEqual(run.call_args.kwargs["encoding"], "utf-8")


class ShowcaseVerificationTest(unittest.TestCase):
    def fixture(self, missing_path=None):
        instance = showcase.Showcase.__new__(showcase.Showcase)
        instance.anchor = date(2026, 10, 11)
        prefix = instance.anchor.isoformat() + "/"
        instance.data = {
            "facilities": {str(i): {"id": str(i), "status": "DRAFT", "courts": []}
                           for i in range(len(showcase.VENUES))},
            "applications": {"demo.linh": {"id": "app-pending", "facilityId": "pending"},
                             "demo.trang": {"id": "app-draft", "facilityId": "draft"}},
            "scenarios": {
                prefix + "booking": {"bookingId": "old-booking", "paymentId": "current-payment"},
                "2026-10-10/booking": {"bookingId": "previous-booking", "paymentId": "old-payment"},
            },
        }
        # Older fixture records exist, but neither appears in the latest-200 lists.
        responses = {
            "/facilities": [],
            "/admin/bookings": [{"id": f"recent-booking-{i}"} for i in range(200)],
            "/admin/bookings/old-booking": {"booking": {"id": "old-booking", "status": "CONFIRMED"}},
            "/admin/payments": [{"id": "current-payment", "status": "SUCCESS"}] + [
                {"id": f"recent-payment-{i}"} for i in range(199)
            ],
            "/admin/payments/old-payment": {"payment": {"id": "old-payment", "status": "SUCCESS"}},
            "/transfers/acquisitions/sold": {"state": "SUCCESS", "bookingId": "old-booking"},
            "/bookings/old-booking": {"booking": {"currentHolderId": "buyer"}},
            "/owner-applications/app-pending": {"application": {"state": "PENDING_APPROVAL"}},
            "/owner-applications/app-draft": {"application": {"state": "DRAFT"}},
            "/owner/facilities/pending/courts": [],
            "/owner/facilities/draft/courts": [],
            "/transfers": [],
        }
        for i, state in enumerate(("CONFIRMED", "CONFIRMED", "GROUP_CANCELLED"), 1):
            group_id = f"group-{i}"
            instance.data["scenarios"][prefix + group_id] = {"groupId": group_id, "actor": "customer"}
            responses["/groups/" + group_id] = {"state": state, "totalPaid": 100, "booking": {"amount": 100}}
        for i, state in enumerate(("ACTIVE", "ACTIVE", "ACTIVE", "COMPLETED"), 1):
            name = f"transfer-{i}"
            instance.data["scenarios"][prefix + name] = {"listingId": name}
            responses["/transfers/" + name] = {"state": state}
        instance.data["scenarios"][prefix + "transfer-4"]["acquisitionId"] = "sold"

        def get(path, actor=None):
            if path == missing_path:
                raise showcase.ApiError(404, path, "Not found")
            if path.startswith("/bookings/search?"):
                return {"items": []}
            return responses[path]

        instance.api = Mock()
        instance.api.get.side_effect = get
        instance.api.sessions = {"customer2": {"user": {"id": "buyer"}}}
        instance.save = Mock()
        instance.report = Mock()
        instance.verify_catalog = Mock(return_value=0)
        return instance

    def test_verify_finds_recorded_items_outside_monitoring_lists(self):
        instance = self.fixture()
        with patch("builtins.print"):
            instance.verify()
        self.assertEqual(instance.data["summary"]["bookings"], 1)
        self.assertEqual(instance.data["summary"]["payments"], 2)
        instance.api.get.assert_any_call("/admin/bookings/old-booking", "admin")
        instance.api.get.assert_any_call("/admin/payments/old-payment", "admin")
        requested = [c.args for c in instance.api.get.call_args_list]
        self.assertNotIn(("/admin/bookings/previous-booking", "admin"), requested)
        self.assertNotIn(("/admin/payments/current-payment", "admin"), requested)

    def test_verify_rejects_genuinely_missing_recorded_items(self):
        for path in ("/admin/bookings/old-booking", "/admin/payments/old-payment"):
            with self.subTest(path=path):
                with self.assertRaisesRegex(RuntimeError, "booking/payment records are missing"):
                    self.fixture(missing_path=path).verify()


class ShowcaseMediaTest(unittest.TestCase):
    def test_gallery_and_court_photos_are_not_duplicated_on_rerun(self):
        instance = showcase.Showcase.__new__(showcase.Showcase)
        images = []
        instance.api = Mock()
        instance.api.get.side_effect = lambda *_: list(images)

        def upload(method, path, **kwargs):
            court = path.partition("?courtId=")[2] or None
            image = {"id": str(len(images)), "courtId": court}
            images.append(image)
            return image

        instance.api.request.side_effect = upload
        courts = [{"id": "court-a"}, {"id": "court-b"}]
        instance.images("facility", "Tennis", courts, "owner")
        self.assertEqual([image["courtId"] for image in images], [None, None, "court-a", "court-b"])
        instance.images("facility", "Tennis", courts, "owner")
        self.assertEqual(instance.api.request.call_count, 4)
        self.assertEqual([call.kwargs["upload"][2] for call in instance.api.request.call_args_list],
                         ["image/png", "image/jpeg", "image/png", "image/png"])

    def test_manifest_for_another_database_is_rejected_without_overwriting_it(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.json"
            api = FixtureApi()
            api.sessions = {"owner": {"user": {"id": "original-owner"}}}
            showcase.Showcase(api, manifest, date(2026, 10, 14))
            original = manifest.read_bytes()
            api.sessions["owner"]["user"]["id"] = "another-database-owner"
            with self.assertRaisesRegex(RuntimeError, "another demo database"):
                showcase.Showcase(api, manifest, date(2026, 10, 14))
            self.assertEqual(manifest.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
