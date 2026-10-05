# SportHub implementation progress

Technical tracking only. `Document/` remains read-only and authoritative for business requirements.

## Delivery checklist

- DONE — Identity core slice: multi-role, register/verify/login/refresh/logout/reset/profile, session validation, Gateway authentication, real auth routes. Eight database/API tests and live Gateway+Mailpit auth smoke pass. Owner applications/Staff management remain separate pending capabilities.
- DONE — Facility core slice: service/schema/API, courts/categories/amenities/media/maintenance, nested owner routes. Media replacement and durable old-object cleanup pass four database tests and real Gateway/MinIO smoke. Approval remains separate.
- DONE — Schedule core slice: multi-interval migration, exceptions, effective pricing, traceable quote/preview, real owner screens; four PostgreSQL tests, OpenAPI validation and live Gateway quote/preview smoke pass.
- DONE — Booking core: discovery/availability, database-enforced hold/conflicts, immutable quote, lifecycle and staff operations. Browser booking/payment/confirmation/QR pass. Live Staff check-in and completion after the actual scheduled end pass, including role/time/duplicate guards. Approved SCHEDULE_READ implemented without Staff write access.
- DONE — Payment demo core slice: signed HTTP callback, amount verification/idempotency, durable confirmed outbox, Booking inbox, reconciliation for late outcomes; three PostgreSQL tests and live Payment→RabbitMQ→Booking confirmation smoke pass. Refund execution remains BLOCKED_RULE; refund requests persist for review.
- DONE — Group core: Booking-owned groups, expiring signed invitation/QR/PNG, membership, equal/custom VND split, immutable allocations during in-flight payment, self/Owner contribution, progress, confirmation and timeout. PostgreSQL tests, live two-member Gateway/Payment/RabbitMQ smoke and browser creation/invite/split/payment/100% confirmation pass. Paid membership mutations and refund execution remain isolated BLOCKED_RULE.
- IN_PROGRESS — Transfer marketplace: independent listing, acquisition and usage-right handoff. Financial settlement remains BLOCKED_RULE because escrow release rules conflict.
- NOT_STARTED — Admin/reporting: APIs in owning domains; real source aggregates.
- IN_PROGRESS — Frontend: replace prototype workflows incrementally with routes and typed API calls.
- IN_PROGRESS — Infrastructure: reproducible Compose, local-only controlled demo seed, build/test/runtime smoke scripts.

## Isolated BLOCKED_RULE items

- Refund eligibility/percentage: no default financial policy selected.
- Staff pricing permission: Owner-only implementation can proceed; disputed Staff write path disabled.
- RESOLVED — Staff capability catalog explicitly approved by user on 2026-10-05: BOOKING_READ, BOOKING_CREATE_COUNTER, BOOKING_CHECK_IN, BOOKING_COMPLETE, SCHEDULE_READ. Owner-assigned facility binding required.
- Owner application approval orchestration/readiness: facility CRUD kept separate.
- Group changes after contribution payment: unresolved mutation path disabled.
- Transfer escrow release: no automatic payout policy selected.
- Additional role/permission labels from detailed Admin UCs: do not add beyond approved CUSTOMER/OWNER/STAFF/ADMIN.

## Verification

- Baseline checkout: `0fa3181ba42e150c35fc63a07b42f8c435e1924a`.
- Verification commands and results will be recorded per milestone; no feature is DONE from code existence alone.
- Auth: `scripts/demo/smoke-auth.ps1` passes against real PostgreSQL, Gateway, Identity and Mailpit.
- Backend: 41 tests passed (Gateway 9, Common 2, Identity 8, Facility 4, Schedule 4, Booking 9 including scannable QR PNG, Payment 5). All six JARs packaged and restarted healthy after the latest changes.
- Frontend: final typecheck, lint (nine unused prototype/fixture warnings), production build pass with 37 routes including Group/Staff. Browser verification found and corrected missing submit types on Group split/join forms; split/payment flow reverified to CONFIRMED.
- OpenAPI: Identity, Facility, Schedule, Booking/Group and Payment validated with pinned generator v7.15.0; latest Group and media replacement contracts revalidated.
- Runtime: `smoke-booking-payment.ps1`, `smoke-staff-lifecycle.ps1` and `smoke-group.ps1` pass. Existing test-only five-minute court disabled after the completed lifecycle test.
- Compose: all six backend services and frontend configured, build-time Gateway rewrite fixed, health/readiness dependencies included; `docker compose config --quiet` passes. Containerized app runtime remains to verify.
- Local infrastructure: isolated `sporthub-14b1` Compose project, healthy PostgreSQL/Redis/RabbitMQ/MinIO/Mailpit. Existing `sporthub` project untouched.
- Windows runtime: stop only this worktree's Java demo processes before rebuilding JARs; running JARs are locked by Windows.
