#!/usr/bin/env python3
"""Populate a running local SportHub with repeatable demo scenarios."""
import argparse
import hashlib
from collections import Counter
from datetime import date, datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
# Modern Vietnamese demo dates use UTC+07; avoid requiring an IANA database on Windows.
ZONE = timezone(timedelta(hours=7), name="Asia/Ho_Chi_Minh")
MARKER = "Bộ dữ liệu SportHub Showcase."
PERMISSIONS = ["BOOKING_READ", "BOOKING_CREATE_COUNTER", "BOOKING_CHECK_IN",
               "BOOKING_COMPLETE", "SCHEDULE_READ"]
CUSTOMERS = ["demo." + name for name in ("anh", "huy", "linh", "trang", "quang", "mai")]
SPORT_IMAGES = {
    "Pickleball": ("hero-pickleball.png", "hero-paddle.png"),
    "Tennis": ("hero-tennis.png", "court-aerial.jpg"),
    "Cầu lông": ("showcase-badminton.png",),
    "Bóng đá": ("showcase-football.png",),
}
# These are fictional venues; administrative names come from the canonical dataset.
VENUES = [
    ("hn-pickleball", "Demo Pickleball Cầu Giấy", "Pickleball", "01", "00166",
     21.035, 105.793, 3, 60, 140000, 200000, "ACTIVE"),
    ("hn-badminton", "Demo Cầu lông Yên Hòa", "Cầu lông", "01", "00175",
     21.012, 105.789, 4, 60, 80000, 120000, "ACTIVE"),
    ("hn-tennis", "Demo Tennis Tây Hồ", "Tennis", "01", "00103",
     21.067, 105.814, 2, 60, 200000, 300000, "ACTIVE"),
    ("hn-football", "Demo Bóng đá Long Biên", "Bóng đá", "01", "00145",
     21.047, 105.892, 3, 60, 320000, 480000, "ACTIVE"),
    ("hn-pickleball-30", "Demo Pickleball Ba Đình", "Pickleball", "01", "00004",
     21.036, 105.826, 3, 30, 70000, 100000, "ACTIVE"),
    ("dn-badminton", "Demo Cầu lông Hải Châu", "Cầu lông", "48", "20242",
     16.045, 108.216, 4, 30, 40000, 60000, "ACTIVE"),
    ("hcm-tennis", "Demo Tennis An Khánh", "Tennis", "79", "27094",
     10.787, 106.725, 2, 60, 220000, 320000, "ACTIVE"),
    ("hcm-football", "Demo Bóng đá Thủ Đức", "Bóng đá", "79", "26824",
     10.850, 106.770, 3, 60, 350000, 500000, "ACTIVE"),
    ("pending-hn", "Demo Thể thao Hoàn Kiếm - Chờ duyệt", "Pickleball", "01", "00070",
     21.030, 105.850, 2, 60, 150000, 220000, "PENDING_APPROVAL"),
    ("pending-dn", "Demo Cầu lông Sơn Trà - Chờ duyệt", "Cầu lông", "48", "20263",
     16.090, 108.245, 3, 60, 90000, 130000, "PENDING_APPROVAL"),
    ("draft-hn", "Demo Pickleball Nghĩa Đô - Hồ sơ nháp", "Pickleball", "01", "00160",
     21.045, 105.800, 2, 60, 130000, 180000, "DRAFT"),
    ("rejected-hn", "Demo Tennis Long Biên - Cần sửa hồ sơ", "Tennis", "01", "00145",
     21.054, 105.885, 2, 60, 210000, 310000, "REJECTED"),
]


def load_env():
    values = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip().strip("'\"")
    return values


def instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def iso(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def key_for(value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "sporthub-showcase/" + value))


class ApiError(RuntimeError):
    def __init__(self, status, path, message):
        self.status = status
        super().__init__(f"HTTP {status} {path}: {message}")


class Api:
    def __init__(self, base, password):
        self.base, self.password, self.sessions = base.rstrip("/"), password, {}

    def request(self, method, path, body=None, actor=None, key=None, upload=None):
        headers, data = {}, None
        if actor:
            headers["Authorization"] = "Bearer " + self.sessions[actor]["accessToken"]
        if key:
            headers["Idempotency-Key"] = key
        if upload:
            name, content, content_type = upload
            boundary = "sporthub-" + uuid.uuid4().hex
            data = (
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                f"filename=\"{name}\"\r\nContent-Type: {content_type}\r\n\r\n"
            ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        # A rejected rate-limited request has not executed; don't retry ambiguous POST failures.
        for attempt in range(5):
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    raw = response.read()
                    return json.loads(raw)["data"] if raw else None
            except urllib.error.HTTPError as error:
                raw = error.read()
                if error.code == 429 and attempt < 4:
                    time.sleep(1 + attempt)
                    continue
                try:
                    message = json.loads(raw).get("message", "Request failed")
                except (ValueError, AttributeError):
                    message = "Request failed"
                raise ApiError(error.code, path, message) from None

    def get(self, path, actor=None):
        return self.request("GET", path, actor=actor)

    def login(self, actor):
        self.sessions[actor] = self.request("POST", "/auth/login", {
            "identifier": actor + "@sporthub.local", "password": self.password,
        })

    def close(self):
        for actor in self.sessions:
            try:
                self.request("POST", "/auth/logout", actor=actor)
            except (ApiError, urllib.error.URLError):
                print(f"Could not close demo session for {actor}", file=sys.stderr)


class Showcase:
    def __init__(self, api, manifest_path, anchor, frontend_base=None):
        self.api, self.path = api, manifest_path
        self.frontend_base = frontend_base
        self.data = json.loads(self.path.read_text(encoding="utf-8")) if self.path.exists() else {
            "version": 1, "facilities": {}, "applications": {}, "scenarios": {},
        }
        self.anchor = anchor or date.fromisoformat(
            self.data.get("date", datetime.now(ZONE).date().isoformat())
        )
        self.data.update(date=self.anchor.isoformat(), apiBase=api.base)
        owner = getattr(api, "sessions", {}).get("owner", {}).get("user", {}).get("id")
        if owner:
            runtime = hashlib.sha256((api.base + "/" + owner).encode()).hexdigest()
            if self.data.get("runtime") not in (None, runtime):
                raise RuntimeError("Manifest belongs to another demo database; use a different --manifest path")
            self.data["runtime"] = runtime
        self.categories = {c["name"]: c["id"] for c in api.get("/sport-categories") if c["active"]}
        self.provinces = json.loads(
            (ROOT / "frontend/features/facility/vietnam-administrative-divisions.json").read_text(encoding="utf-8")
        )["provinces"]
        self.save()

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        pending = self.path.with_suffix(".pending.json")
        pending.write_text(json.dumps(self.data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        pending.replace(self.path)

    def facility_input(self, spec, index):
        slug, name, sport, province_code, ward_code, lat, lng, _, minutes, *_ = spec
        province = next(p for p in self.provinces if p["code"] == province_code)
        ward = next(w for w in province["wards"] if w["code"] == ward_code)
        return {
            "name": name, "phone": f"+8400000{index:04}", "contactEmail": slug + "@sporthub.local",
            "addressLine": f"Khu thể thao demo, số {10 + index} đường Thể Thao",
            "provinceCode": province_code, "province": province["name"],
            "wardCode": ward_code, "ward": ward["name"],
            "description": (
                f"{MARKER} Cơ sở mô phỏng để trải nghiệm tìm và đặt sân {sport}. "
                f"Slot {minutes} phút; giá ngày thường, cuối tuần và giờ cao điểm. "
                "Mở cửa 06:00–23:00 mỗi ngày; có đèn cho lịch buổi tối, "
                "khu nghỉ giữa trận, chỗ gửi xe và hỗ trợ thuê dụng cụ tại quầy. "
                "Nên đến trước giờ đặt 10 phút để chuẩn bị. "
                "Ảnh thể thao minh họa dùng cho demo; không phải địa điểm thương mại thật."
            ),
            "timezone": "Asia/Ho_Chi_Minh", "latitude": lat, "longitude": lng,
            "amenities": ["Bãi đỗ xe", "Phòng thay đồ", "Nước uống", "Đèn chiếu sáng", "Wi-Fi"],
        }

    def configure(self, facility, spec, actor):
        slug, _, sport, _, _, _, _, count, minutes, regular, peak, _ = spec
        fid = facility["id"]
        courts = self.api.get(f"/owner/facilities/{fid}/courts", actor)
        for number in range(1, count + 1):
            code = "SHOWCASE-" + slug.upper() + "-" + str(number)
            if not any(c["code"] == code for c in courts):
                courts.append(self.api.request("POST", f"/owner/facilities/{fid}/courts", {
                    "code": code, "name": f"Sân {sport} {number:02}",
                    "sportCategoryId": self.categories[sport], "enabled": True,
                    "description": (
                        f"Sân {sport} {number:02} — khu vực "
                        f"{'trong nhà' if sport == 'Cầu lông' else 'ngoài trời'}, "
                        "có vạch sân tiêu chuẩn, đèn chiếu sáng và ghế nghỉ. "
                        f"Đặt theo khung {minutes} phút; giá đã bao gồm sử dụng sân "
                        "và thiết bị cố định. Dụng cụ cá nhân có thể mang theo hoặc thuê tại quầy."
                    ),
                }, actor))
        if not self.api.get(f"/schedules/facilities/{fid}/hours", actor):
            self.api.request("PUT", f"/schedules/facilities/{fid}/hours", {
                "intervals": [
                    {"dayOfWeek": d, "opensAt": "06:00", "closesAt": "23:00", "slotMinutes": minutes}
                    for d in range(1, 8)
                ],
            }, actor)
        rules = self.api.get(f"/schedules/facilities/{fid}/pricing", actor)
        for day in range(1, 8):
            for start, end, price, label in [
                ("06:00", "17:00", regular, "Demo giờ thường"),
                ("17:00", "23:00", peak, "Demo giờ cao điểm"),
            ]:
                if any(r["active"] and r["dayOfWeek"] == day and r["label"] == label for r in rules):
                    continue
                self.api.request("POST", f"/schedules/facilities/{fid}/pricing", {
                    "dayOfWeek": day, "startsAt": start, "endsAt": end,
                    "pricePerSlot": int(price * 1.2) if day >= 6 else price,
                    "priority": 0, "label": label,
                    "effectiveFrom": (self.anchor - timedelta(days=1)).isoformat(), "currency": "VND",
                }, actor)
        documents = self.api.get(f"/owner/facilities/{fid}/documents", actor)
        for name, title in [
            ("demo-giay-to-dinh-danh.pdf", "DEMO IDENTITY DOCUMENT"),
            ("demo-giay-to-dia-diem.pdf", "DEMO VENUE DOCUMENT"),
        ]:
            if not any(d["name"] == name for d in documents):
                self.api.request("POST", f"/owner/facilities/{fid}/documents", actor=actor,
                                 upload=(name, demo_pdf(title, facility["name"]), "application/pdf"))
        self.images(fid, sport, courts, actor)
        return sorted(courts, key=lambda c: c["code"])

    def images(self, fid, sport, courts, actor):
        """Persist cover/gallery plus court-specific media; never duplicate on rerun."""
        path = f"/owner/facilities/{fid}/images"
        existing = self.api.get(path, actor)
        assets = SPORT_IMAGES[sport]
        required = [(None, image) for image in assets]
        required.extend((court["id"], assets[0]) for court in courts)
        counts = Counter(image.get("courtId") for image in existing)
        seen = Counter()
        for court_id, name in required:
            seen[court_id] += 1
            if counts[court_id] >= seen[court_id]:
                continue
            image_path = ROOT / "frontend/public/images" / name
            image = image_path.read_bytes()
            media_type = "image/jpeg" if image_path.suffix == ".jpg" else "image/png"
            query = "?courtId=" + court_id if court_id else ""
            uploaded = self.api.request("POST", path + query, actor=actor, upload=(
                "demo-" + name, image, media_type,
            ))
            existing.append(uploaded)
        return existing

    def venues(self):
        owned = self.api.get("/owner/facilities", "owner")
        for index, spec in enumerate(VENUES, 1):
            slug, name, *_, target = spec
            facility = next((f for f in owned if f["name"] == name), None)
            if not facility:
                facility = self.api.request("POST", "/owner/facilities", self.facility_input(spec, index), "owner")
                owned.append(facility)
            if not (facility.get("description") or "").startswith(MARKER):
                raise RuntimeError(f"Existing venue does not belong to this fixture: {name}")
            fid = facility["id"]
            self.data["facilities"][slug] = {"id": fid, "name": name, "status": facility["status"]}
            self.save()
            courts = (self.configure(facility, spec, "owner") if facility["status"] in ("DRAFT", "ACTIVE")
                      else self.api.get(f"/owner/facilities/{fid}/courts", "owner"))
            self.data["facilities"][slug]["courts"] = [
                {"id": c["id"], "name": c["name"], "code": c["code"]} for c in courts
            ]
            self.data["facilities"][slug].update(
                sport=spec[2], provinceCode=spec[3], wardCode=spec[4],
                latitude=spec[5], longitude=spec[6], slotMinutes=spec[8],
                regularPrice=spec[9], peakPrice=spec[10],
            )
            if target != "DRAFT" and facility["status"] == "DRAFT":
                self.api.request("POST", f"/owner/facilities/{fid}/submit", actor="owner")
                facility["status"] = "PENDING_APPROVAL"
            if target in ("ACTIVE", "REJECTED") and facility["status"] == "PENDING_APPROVAL":
                facility = self.api.request("POST", f"/admin/facilities/{fid}/decision", {
                    "action": "APPROVE" if target == "ACTIVE" else "REJECT",
                    "reason": "Hồ sơ mô phỏng đầy đủ để demo đặt sân." if target == "ACTIVE" else
                              "Hồ sơ demo cần sửa thông tin địa điểm và bổ sung chứng từ rõ ràng.",
                }, "admin")
            self.data["facilities"][slug]["status"] = facility["status"]
            if facility["status"] == "ACTIVE":
                if not self.api.get(f"/owner/facilities/{fid}/transfer-policy", "owner")["configured"]:
                    self.api.request("PUT", f"/owner/facilities/{fid}/transfer-policy", {
                        "enabled": True, "minLeadSeconds": 3600,
                    }, "owner")
                bindings = self.api.get(f"/staff-bindings/facilities/{fid}", "owner")
                staff_id = self.api.sessions["staff"]["user"]["id"]
                if not any(b["userId"] == staff_id and b["active"] for b in bindings):
                    self.api.request("POST", f"/staff-bindings/facilities/{fid}", {
                        "identifier": "staff@sporthub.local", "permissions": PERMISSIONS,
                    }, "owner")
            self.save()
            print(f"Venue ready: {name} ({facility['status']}, {len(courts)} courts)", flush=True)

    def enrich_starter(self):
        """Fill the stock local seeder's existing center without recreating it."""
        starter = next((f for f in self.api.get("/owner/facilities", "owner")
                        if f["name"] == "SportHub Demo Center"), None)
        if not starter:
            return
        fid = starter["id"]
        detail = self.api.get(f"/owner/facilities/{fid}", "owner")
        body = {key: detail.get(key) for key in (
            "name", "phone", "addressLine", "provinceCode", "province", "wardCode", "ward",
            "description", "timezone", "latitude", "longitude", "amenities", "contactEmail")}
        body["description"] = body["description"] or (
            f"{MARKER} Trung tâm thể thao mẫu gồm sân tennis và pickleball, "
            "có lịch mở cửa, giá theo khung giờ, khu nghỉ và chỗ gửi xe. "
            "Ảnh minh họa cho dữ liệu local; không phải địa điểm thương mại thật.")
        body["contactEmail"] = body["contactEmail"] or "owner@sporthub.local"
        if body["latitude"] is None and body["longitude"] is None:
            body.update(latitude=21.036, longitude=105.826)
        if any(body[key] != detail.get(key) for key in body):
            self.api.request("PUT", f"/owner/facilities/{fid}", body, "owner")
        courts = self.api.get(f"/owner/facilities/{fid}/courts", "owner")
        for sport, category in self.categories.items():
            matching = [court for court in courts if court["sportCategoryId"] == category]
            for court in matching:
                if not court.get("description"):
                    self.api.request("PUT", f"/owner/courts/{court['id']}", {
                        "code": court["code"], "name": court["name"], "sportCategoryId": category,
                        "enabled": court["enabled"],
                        "description": f"Sân {sport} demo có vạch sân, thiết bị cố định và đèn chiếu sáng. Giá và giờ trống được tính từ lịch sân.",
                    }, "owner")
            if matching:
                self.images(fid, sport, matching, "owner")
        self.data["starterFacility"] = {"id": fid, "name": starter["name"], "courts": len(courts)}
        self.save()
        print("Starter demo center ready: details and sport-specific images populated", flush=True)

    def applications(self):
        for index, actor in enumerate(("demo.linh", "demo.trang"), 1):
            target = "PENDING_APPROVAL" if index == 1 else "DRAFT"
            slug = "app-" + actor.split(".")[1]
            spec = (slug, "Demo " + ("Pickleball Giảng Võ" if index == 1 else "Tennis Yên Hòa") +
                    " - Đăng ký Owner", "Pickleball" if index == 1 else "Tennis", "01",
                    "00025" if index == 1 else "00175", 21.026, 105.817 if index == 1 else 105.790,
                    2, 60, 150000, 220000, target)
            applications = self.api.get("/owner-applications", actor)
            application = next((a for a in applications if a["facilityName"] == spec[1]), None)
            if not application:
                representative = self.api.sessions[actor]["user"]["fullName"]
                application = self.api.request("POST", "/owner-applications", {
                    "legal": {
                        "representativeName": representative, "identityNumber": f"DEMO-ID-{index:04}",
                        "businessName": "Hộ kinh doanh Thể thao Demo " + actor.split(".")[1].title(),
                        "businessLicense": f"DEMO-LICENSE-{index:04}", "taxCode": f"DEMO-TAX-{index:04}",
                        "bankName": "Ngân hàng mô phỏng Demo", "bankAccountHolder": representative,
                        "bankAccountNumber": f"DEMO-BANK-{index:04}",
                    }, "facility": self.facility_input(spec, 20 + index),
                }, actor)
            self.data["applications"][actor] = {
                "id": application["id"], "facilityId": application["facilityId"],
                "facilityName": application["facilityName"], "state": application["state"],
            }
            self.save()
            if application["state"] == "DRAFT":
                self.api.request("POST", f"/owner-applications/{application['id']}/initialize", actor=actor)
                facility = self.api.get(f"/owner/facilities/{application['facilityId']}", actor)
                self.configure(facility, spec, actor)
                if target == "PENDING_APPROVAL":
                    application = self.api.request("POST", f"/owner-applications/{application['id']}/submit", actor=actor)
                    for _ in range(30):
                        if application["state"] != "SUBMITTING":
                            break
                        time.sleep(0.5)
                        application = self.api.get(f"/owner-applications/{application['id']}", actor)["application"]
                    if application["state"] != "PENDING_APPROVAL":
                        raise RuntimeError("Owner application submission did not finish")
            self.data["applications"][actor]["state"] = application["state"]
            self.save()
            print(f"Owner application ready: {actor} ({application['state']})", flush=True)

    def scenario(self, name):
        identifier = self.anchor.isoformat() + "/" + name
        return identifier, self.data["scenarios"].setdefault(identifier, {})

    def select_slot(self, slug, offset, hour, court_index=0):
        court = self.data["facilities"][slug]["courts"][court_index]
        day = self.anchor + timedelta(days=offset)
        preview = self.api.get(f"/bookings/availability?courtId={court['id']}&date={day}")
        slots = [s for s in preview["slots"] if s["state"] == "AVAILABLE"]
        if hour is not None:
            slots = [s for s in slots if instant(s["startsAt"]).astimezone(ZONE).hour >= hour]
        if not slots:
            raise RuntimeError(f"No available demo slot for {slug} on {day}")
        return court["id"], slots[0]

    def hold(self, name, actor, slug, offset, hour, court_index=0, counter=False):
        identifier, record = self.scenario(name)
        if not record.get("holdId"):
            court_id, slot = self.select_slot(slug, offset, hour, court_index)
            hold = self.api.request("POST", "/bookings/holds" + ("?counter=true" if counter else ""), {
                "courtId": court_id, "startsAt": slot["startsAt"], "endsAt": slot["endsAt"],
                "expectedAmount": slot["amount"],
            }, actor, key_for(identifier + "/hold"))
            record.update(holdId=hold["id"], startsAt=slot["startsAt"], endsAt=slot["endsAt"], actor=actor)
            self.save()
        return identifier, record

    def booking(self, name, actor, slug, offset, hour, court_index=0, counter=False):
        identifier, record = self.scenario(name)
        if not record.get("bookingId"):
            identifier, record = self.hold(name, actor, slug, offset, hour, court_index, counter)
            body = {"holdId": record["holdId"]}
            if counter:
                body.update(guestName="Khách vãng lai Demo", guestPhone="+84000000999")
            booking = self.api.request("POST", "/bookings" + ("?counter=true" if counter else ""),
                                       body, actor, key_for(identifier + "/booking"))
            record.update(bookingId=booking["id"], actor=actor)
            self.save()
        return self.api.get("/bookings/" + record["bookingId"], actor)["booking"], record

    def pay(self, name, actor, body, outcome="SUCCESS"):
        identifier, record = self.scenario(name)
        if not record.get("paymentId"):
            payment = self.api.request("POST", "/payments", body, actor, key_for(identifier + "/payment"))
            record.update(paymentId=payment["id"], actor=actor)
            self.save()
        payment = self.api.get("/payments/" + record["paymentId"], actor)
        if payment["status"] == "PENDING":
            payment = self.api.request("POST", f"/payments/{payment['id']}/demo/complete", {"outcome": outcome}, actor)
        if payment["status"] != outcome:
            raise RuntimeError(f"Unexpected demo payment status: {payment['status']}")
        return payment

    def wait_booking(self, booking_id, actor, states):
        for _ in range(60):
            detail = self.api.get("/bookings/" + booking_id, actor)
            if detail["booking"]["status"] in states:
                return detail
            time.sleep(0.4)
        raise RuntimeError("Booking payment event did not reach the expected state")

    def bookings(self):
        active = [v[0] for v in VENUES if v[-1] == "ACTIVE"]
        for index, slug in enumerate(active):
            actor = "customer" if index < 4 else "customer2"
            name = f"confirmed-{index + 1:02}"
            booking, _ = self.booking(name, actor, slug, 1 + index % 4, 18)
            self.pay(name + "-payment", actor, {"bookingId": booking["id"]})
            self.wait_booking(booking["id"], actor, {"CONFIRMED", "CHECKED_IN", "COMPLETED"})
            print(f"Confirmed booking ready: {slug}", flush=True)
        for index, slug in enumerate(active[:4]):
            actor = "customer" if index < 2 else "customer2"
            name = f"morning-{index + 1:02}"
            booking, _ = self.booking(name, actor, slug, 5, 8, 1)
            self.pay(name + "-payment", actor, {"bookingId": booking["id"]})
            self.wait_booking(booking["id"], actor, {"CONFIRMED"})
        for index, actor in enumerate(("customer", "customer2")):
            booking, _ = self.booking(f"cancelled-{index}", actor, active[index], 3, 10)
            if booking["status"] == "PENDING":
                self.api.request("POST", f"/bookings/{booking['id']}/cancel", {
                    "reason": "Khách demo đổi kế hoạch trước khi thanh toán.",
                }, actor)
        booking, _ = self.booking("failed-payment", "customer", active[2], 4, 11)
        self.pay("failed-payment-order", "customer", {"bookingId": booking["id"]}, "FAILED")
        if booking["status"] == "PENDING":
            self.api.request("POST", f"/bookings/{booking['id']}/cancel", {
                "reason": "Khách demo hủy sau khi thanh toán thất bại.",
            }, "customer")
        booking, _ = self.booking("counter", "staff", active[0], 2, 15, counter=True)
        self.pay("counter-payment", "staff", {"bookingId": booking["id"]})
        self.wait_booking(booking["id"], "staff", {"CONFIRMED"})

    def groups(self):
        players = ["customer", "customer2", "demo.anh", "demo.huy"]
        for index, (slug, name, actor) in enumerate([
            ("hn-pickleball", "Demo CLB Pickleball cuối tuần", "customer"),
            ("hn-tennis", "Demo Nhóm Tennis giao hữu", "customer"),
            ("hn-badminton", "Demo Cầu lông - Lịch đã hủy", "customer2"),
        ], 1):
            scenario_name = f"group-{index}"
            identifier, record = self.scenario(scenario_name)
            if not record.get("groupId"):
                identifier, record = self.hold(scenario_name, actor, slug, 6 + index, 19, 1)
                group = self.api.request("POST", "/groups", {
                    "holdId": record["holdId"], "name": name, "maxMembers": 8,
                }, actor, key_for(identifier + "/group"))
                record.update(groupId=group["id"], bookingId=group["id"], actor=actor)
                self.save()
            group = self.api.get("/groups/" + record["groupId"], actor)
            if group["state"] == "GROUP_PENDING":
                if not group["allocationsLocked"]:
                    invite = self.api.get(f"/groups/{group['id']}/invite", actor)
                    for player in players:
                        user_id = self.api.sessions[player]["user"]["id"]
                        if not any(m["userId"] == user_id and m["active"] for m in group["members"]):
                            group = self.api.request("POST", "/groups/join", {"code": invite["code"]}, player)
                if index == 3:
                    self.api.request("POST", f"/groups/{group['id']}/cancel", {
                        "reason": "Nhóm demo đổi lịch trước khi đóng góp.",
                    }, actor)
                else:
                    if not group["allocationsLocked"]:
                        group = self.api.request("PUT", f"/groups/{group['id']}/allocations", {"mode": "EQUAL"}, actor)
                    for player in players:
                        member = next(m for m in group["members"]
                                      if m["userId"] == self.api.sessions[player]["user"]["id"])
                        if member["paymentState"] == "PAID":
                            continue
                        self.pay(scenario_name + "-payment-" + player, player, {
                            "bookingId": group["id"], "memberId": member["id"],
                        })
                        for _ in range(60):
                            current = self.api.get(f"/groups/{group['id']}", player)
                            if next(m for m in current["members"] if m["id"] == member["id"])["paymentState"] == "PAID":
                                break
                            time.sleep(0.4)
                        else:
                            raise RuntimeError("Group contribution event did not arrive")
                    self.wait_booking(group["id"], actor, {"CONFIRMED"})
            print(f"Group ready: {name}", flush=True)

    def transfers(self):
        for index in range(1, 5):
            _, source = self.scenario(f"confirmed-{index:02}")
            booking = self.api.get("/bookings/" + source["bookingId"], "customer")["booking"]
            name = f"transfer-{index}"
            identifier, record = self.scenario(name)
            if not record.get("listingId"):
                listing = self.api.request("POST", "/transfers", {
                    "bookingId": booking["id"], "price": int(booking["amount"] * 0.8),
                    "deadline": iso(instant(booking["startsAt"]) - timedelta(hours=1)),
                }, "customer", key_for(identifier + "/listing"))
                record.update(listingId=listing["id"], actor="customer")
                self.save()
            if index == 4:
                listing = self.api.get("/transfers/" + record["listingId"], "customer")
                if listing["state"] != "COMPLETED":
                    if not record.get("acquisitionId"):
                        acquisition = self.api.request("POST", f"/transfers/{listing['id']}/acquisitions",
                                                       actor="customer2", key=key_for(identifier + "/acquisition"))
                        record["acquisitionId"] = acquisition["id"]
                        self.save()
                    self.pay(name + "-purchase", "customer2", {
                        "bookingId": booking["id"], "acquisitionId": record["acquisitionId"],
                    })
                    for _ in range(60):
                        acquisition = self.api.get("/transfers/acquisitions/" + record["acquisitionId"], "customer2")
                        if acquisition["state"] == "SUCCESS":
                            break
                        time.sleep(0.4)
                    else:
                        raise RuntimeError("Transfer handoff did not finish")
            print(f"Transfer ready: {index} ({'sold' if index == 4 else 'available'})", flush=True)

    def live_checkin(self):
        _, record = self.scenario("live-checkin")
        if self.anchor != datetime.now(ZONE).date():
            return
        if not record.get("bookingId"):
            try:
                _, slot = self.select_slot("hn-pickleball-30", 0, None)
            except RuntimeError:
                print("Live check-in skipped: no remaining slot today", flush=True)
                return
            if instant(slot["startsAt"]) - datetime.now(timezone.utc) > timedelta(minutes=30):
                return
        booking, _ = self.booking("live-checkin", "customer", "hn-pickleball-30", 0, None)
        if booking["status"] in ("CHECKED_IN", "COMPLETED"):
            return
        self.pay("live-checkin-payment", "customer", {"bookingId": booking["id"]})
        detail = self.wait_booking(booking["id"], "customer", {"CONFIRMED"})
        self.api.request("POST", f"/bookings/{booking['id']}/check-in", {"token": detail["checkinToken"]}, "staff")
        print("Live Staff check-in scenario ready", flush=True)

    def admin_records(self, path, field, identifiers):
        records = {r["id"]: r for r in self.api.get(path, "admin") if r["id"] in identifiers}
        # Monitoring lists contain only the latest 200 records; look up older fixtures individually.
        for identifier in sorted(identifiers - records.keys()):
            try:
                records[identifier] = self.api.get(path + "/" + identifier, "admin")[field]
            except ApiError as error:
                if error.status == 404:
                    raise RuntimeError("Some demo booking/payment records are missing") from None
                raise
        return list(records.values())

    def verify_catalog(self):
        """Check the fields and actual image objects consumed by frontend screens."""
        entries = [(record["id"], "owner") for record in self.data["facilities"].values()]
        entries.extend((record["facilityId"], actor) for actor, record in self.data["applications"].items())
        if self.data.get("starterFacility"):
            entries.append((self.data["starterFacility"]["id"], "owner"))
        media_count = 0
        for fid, actor in entries:
            detail = self.api.get(f"/owner/facilities/{fid}", actor)
            fields = ("name", "phone", "contactEmail", "description", "addressLine", "provinceCode",
                      "province", "wardCode", "ward", "timezone", "amenities")
            if any(not detail.get(field) for field in fields) or any(
                    detail.get(field) is None for field in ("latitude", "longitude")):
                raise RuntimeError("Demo facility detail is incomplete: " + detail.get("name", fid))
            courts = self.api.get(f"/owner/facilities/{fid}/courts", actor)
            images = self.api.get(f"/owner/facilities/{fid}/images", actor)
            if not any(image.get("courtId") is None for image in images):
                raise RuntimeError("Demo facility cover image missing: " + detail["name"])
            for court in courts:
                if not court.get("description") or not any(image.get("courtId") == court["id"] for image in images):
                    raise RuntimeError("Demo court description/image missing: " + court["name"])
            for image in images:
                with urllib.request.urlopen(image["url"], timeout=30) as response:
                    content = response.read()
                    if response.status != 200 or not response.headers.get("Content-Type", "").startswith("image/") or not content:
                        raise RuntimeError("Demo image object is unavailable")
            media_count += len(images)
        return media_count

    def verify(self):
        if len(self.data["facilities"]) != len(VENUES) or len(self.data["applications"]) != 2:
            raise RuntimeError("The showcase seed has not finished; run it without --verify-only")
        active = {f["id"] for f in self.data["facilities"].values() if f["status"] == "ACTIVE"}
        public = self.api.get("/facilities")
        if not active.issubset({f["id"] for f in public}):
            raise RuntimeError("Some approved demo venues are missing from public discovery")
        day = (self.anchor + timedelta(days=1)).isoformat()
        discovery = self.api.get("/bookings/search?" + urllib.parse.urlencode({"q": "Demo", "date": day}))
        demo_courts = [c for c in discovery["items"] if c["facility"]["id"] in active]
        expected = sum(len(f["courts"]) for f in self.data["facilities"].values() if f["status"] == "ACTIVE")
        if len(demo_courts) != expected or any(c["fromPrice"] <= 0 or c["availableSlots"] <= 0 for c in demo_courts):
            raise RuntimeError("Demo discovery has missing courts, prices or available slots")
        booking_ids = {r["bookingId"] for k, r in self.data["scenarios"].items()
                       if k.startswith(self.anchor.isoformat() + "/") and r.get("bookingId")}
        bookings = self.admin_records("/admin/bookings", "booking", booking_ids)
        payment_ids = {r["paymentId"] for r in self.data["scenarios"].values() if r.get("paymentId")}
        payments = self.admin_records("/admin/payments", "payment", payment_ids)
        groups = [self.api.get("/groups/" + r["groupId"], r["actor"])
                  for k, r in self.data["scenarios"].items()
                  if k.startswith(self.anchor.isoformat() + "/") and r.get("groupId")]
        for group in groups:
            if group["state"] == "CONFIRMED" and group["totalPaid"] != group["booking"]["amount"]:
                raise RuntimeError("Confirmed group contributions do not balance")
        if Counter(g["state"] for g in groups) != Counter({"CONFIRMED": 2, "GROUP_CANCELLED": 1}):
            raise RuntimeError("Demo groups have unexpected states")
        listings = [self.api.get("/transfers/" + r["listingId"], "customer")
                    for k, r in self.data["scenarios"].items()
                    if k.startswith(self.anchor.isoformat() + "/") and r.get("listingId")]
        if Counter(l["state"] for l in listings) != Counter({"ACTIVE": 3, "COMPLETED": 1}):
            raise RuntimeError("Demo transfers have unexpected states")
        _, sold = self.scenario("transfer-4")
        acquisition = self.api.get("/transfers/acquisitions/" + sold["acquisitionId"], "customer2")
        transferred = self.api.get("/bookings/" + acquisition["bookingId"], "customer2")["booking"]
        if acquisition["state"] != "SUCCESS" or transferred["currentHolderId"] != self.api.sessions["customer2"]["user"]["id"]:
            raise RuntimeError("Purchased demo transfer did not change the booking holder")
        applications = [self.api.get("/owner-applications/" + a["id"], actor)["application"]
                        for actor, a in self.data["applications"].items()]
        if Counter(a["state"] for a in applications) != Counter({"PENDING_APPROVAL": 1, "DRAFT": 1}):
            raise RuntimeError("Demo Owner applications have unexpected states")
        media_count = self.verify_catalog()
        summary = {
            "newFacilities": len(self.data["facilities"]) + len(self.data["applications"]),
            "newCourts": sum(len(f["courts"]) for f in self.data["facilities"].values()) + sum(
                len(self.api.get(f"/owner/facilities/{a['facilityId']}/courts", actor))
                for actor, a in self.data["applications"].items()
            ),
            "publicDemoFacilities": len(active), "searchableDemoCourts": len(demo_courts),
            "bookings": len(bookings), "bookingStates": dict(Counter(b["status"] for b in bookings)),
            "payments": len(payments), "paymentStates": dict(Counter(p["status"] for p in payments)),
            "groups": len(groups), "groupStates": dict(Counter(g["state"] for g in groups)),
            "transfers": len(listings), "transferStates": dict(Counter(l["state"] for l in listings)),
            "marketplaceListings": len([l for l in self.api.get("/transfers", "customer2") if l["facilityId"] in active]),
            "ownerApplications": len(self.data["applications"]),
            "ownerApplicationStates": dict(Counter(a["state"] for a in applications)),
            "verifiedImages": media_count,
        }
        self.data.update(verifiedAt=datetime.now(ZONE).isoformat(), summary=summary)
        self.save()
        self.report(summary)
        print(json.dumps(summary, indent=2, ensure_ascii=False), flush=True)

    def report(self, summary):
        env = load_env()
        base = (getattr(self, "frontend_base", None) or env.get("APP_PUBLIC_URL") or
                f"http://localhost:{env.get('FRONTEND_PORT', '13000')}").rstrip("/")
        fence = chr(96) * 3
        lines = [
            "# Dữ liệu demo SportHub", "", "Kiểm tra lúc: " + self.data["verifiedAt"], "",
            "## Mở màn hình", "",
            f"- [Tìm sân]({base}/search?q=Demo&date={self.anchor + timedelta(days=1)})",
            f"- [Booking của tôi]({base}/customer/bookings)",
            f"- [Nhóm chơi]({base}/customer/groups)",
            f"- [Chuyển nhượng]({base}/customer/transfers)",
            f"- [Cơ sở Owner]({base}/owner/facilities)",
            f"- [Lịch Staff]({base}/staff/schedule)",
            f"- [Admin: Cơ sở]({base}/admin/facilities)",
            f"- [Admin: Đăng ký Owner]({base}/admin/owner-applications)",
            f"- [Admin: Giao dịch]({base}/admin/payments)",
            f"- [Admin: Báo cáo]({base}/admin/reports)", "",
            "## Tài khoản", "", "Mật khẩu chung lấy từ DEMO_PASSWORD trong .env.", "",
            "- customer@sporthub.local: booking, nhóm chơi và tin đang bán.",
            "- customer2@sporthub.local: booking, nhóm chơi và lượt mua chuyển nhượng.",
            "- owner@sporthub.local: cơ sở, lịch giá, nhân viên và hồ sơ cơ sở.",
            "- staff@sporthub.local: lịch và booking tại 8 cơ sở Showcase đã phân công.",
            "- admin@sporthub.local: tài khoản, duyệt hồ sơ, booking, thanh toán và báo cáo.",
            "- demo.anh@sporthub.local, demo.huy@sporthub.local: thành viên nhóm.",
            "- demo.linh@sporthub.local: hồ sơ đăng ký Owner đang chờ duyệt.",
            "- demo.trang@sporthub.local: hồ sơ đăng ký Owner đang là nháp.",
            "- demo.quang@sporthub.local, demo.mai@sporthub.local: khách hàng mẫu.", "",
            "## Kết quả xác minh", "", fence + "json",
            json.dumps(summary, indent=2, ensure_ascii=False), fence, "",
            "## Hồ sơ Owner", "",
        ]
        for application in self.data["applications"].values():
            lines.append(f"- [{application['facilityName']}]({base}/admin/owner-applications/{application['id']}): {application['state']}")
        lines.extend(["", "## Cơ sở và sân", "", "Ảnh được lưu thật trong MinIO qua media API; URL ký được cấp mới khi mở trang.", ""])
        for facility in self.data["facilities"].values():
            lines.append(f"- [{facility['name']}]({base}/facilities/{facility['id']}): {facility['status']}, {len(facility['courts'])} sân.")
            for court in facility["courts"]:
                lines.append(f"  - [{court['name']}]({base}/facilities/{facility['id']}/courts/{court['id']}?date={self.anchor + timedelta(days=1)})")
        (self.path.parent / "showcase-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def demo_pdf(title, venue):
    name = unicodedata.normalize("NFKD", venue.replace("Đ", "D").replace("đ", "d")).encode("ascii", "ignore").decode()
    text = (f"BT /F1 18 Tf 50 750 Td ({title}) Tj 0 -35 Td /F1 12 Tf "
            f"(SPORTHUB LOCAL DEMO) Tj 0 -30 Td ({name}) Tj 0 -30 Td "
            "(Synthetic sample for the demo review workflow.) Tj ET")
    content = text.encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        f"<< /Length {len(content)} >>\nstream\n".encode() + content + b"\nendstream",
    ]
    result, offsets = b"%PDF-1.4\n", [0]
    for number, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result += f"{number} 0 obj\n".encode() + obj + b"\nendobj\n"
    start = len(result)
    result += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
    result += b"".join(f"{offset:010} 00000 n \n".encode() for offset in offsets[1:])
    return result + f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode()


def seed_customers():
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
         'exec psql -U "$POSTGRES_USER" -d identity_db -v ON_ERROR_STOP=1'],
        input=(ROOT / "scripts/demo/showcase-customers.sql").read_text(encoding="utf-8"),
        text=True, encoding="utf-8", capture_output=True, cwd=ROOT,
    )
    if result.returncode:
        raise RuntimeError("Demo customer fixtures failed: " + result.stderr.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base", help="Local Gateway API URL")
    parser.add_argument("--date", type=date.fromisoformat, help="Start another booking batch for this date")
    parser.add_argument("--verify-only", action="store_true", help="Verify previously seeded scenarios")
    parser.add_argument("--manifest", type=Path, default=ROOT / "tmp/demo/showcase-manifest.json",
                        help="Separate fixture state for another local database")
    parser.add_argument("--frontend-base", help="Frontend origin for the generated screen links")
    args = parser.parse_args()
    env = load_env()
    if env.get("SPRING_PROFILES_ACTIVE") not in ("local", "demo") or env.get("DEMO_SEED_ENABLED") != "true":
        raise RuntimeError("This script requires the local/demo seed environment")
    base = args.api_base or f"http://localhost:{env.get('GATEWAY_PORT', '8080')}/api/v1"
    if urllib.parse.urlparse(base).hostname not in ("localhost", "127.0.0.1", "::1"):
        raise RuntimeError("This fixture script only targets the local demo")
    api = Api(base, env["DEMO_PASSWORD"])
    try:
        for actor in ("owner", "admin", "staff", "customer", "customer2"):
            api.login(actor)
        if not args.verify_only:
            seed_customers()
        for actor in CUSTOMERS:
            api.login(actor)
        showcase = Showcase(api, args.manifest, args.date, args.frontend_base)
        if not args.verify_only:
            showcase.venues()
            showcase.enrich_starter()
            showcase.applications()
            showcase.bookings()
            showcase.groups()
            showcase.transfers()
            showcase.live_checkin()
        showcase.verify()
    finally:
        api.close()


if __name__ == "__main__":
    try:
        main()
    except (ApiError, RuntimeError, urllib.error.URLError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
