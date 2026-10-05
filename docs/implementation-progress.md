# SportHub implementation progress

Technical tracking only. `Document/` remains read-only and authoritative for business requirements.

## Delivery checklist

- DONE — Identity core slice: multi-role, register/verify/login/refresh/logout/reset/profile, session validation, Gateway authentication, real auth routes. Eight database/API tests and live Gateway+Mailpit auth smoke pass. Owner applications/Staff management remain separate pending capabilities.
- DONE — Facility core slice: service/schema/API, courts/categories/amenities/media/maintenance, nested owner routes. Media replacement and durable old-object cleanup pass four database tests and real Gateway/MinIO smoke. Approval remains separate.
- DONE — Schedule core slice: multi-interval migration, exceptions, effective pricing, traceable quote/preview, real owner screens; four PostgreSQL tests, OpenAPI validation and live Gateway quote/preview smoke pass.
- DONE — Booking core: discovery/availability, database-enforced hold/conflicts, immutable quote, lifecycle and staff operations. Browser booking/payment/confirmation/QR pass. Live Staff check-in and completion after the actual scheduled end pass, including role/time/duplicate guards. Approved SCHEDULE_READ implemented without Staff write access.
- DONE — Payment demo core slice: signed HTTP callback, amount verification/idempotency, durable confirmed outbox, Booking inbox, reconciliation for late outcomes; three PostgreSQL tests and live Payment→RabbitMQ→Booking confirmation smoke pass. Refund execution remains BLOCKED_RULE; refund requests persist for review.
- DONE — Group core: Booking-owned groups, expiring signed invitation/QR/PNG, membership, equal/custom VND split, immutable allocations during in-flight payment, self/Owner contribution, progress, confirmation and timeout. PostgreSQL tests, live two-member Gateway/Payment/RabbitMQ smoke and browser creation/invite/split/payment/100% confirmation pass. Paid membership mutations and refund execution remain isolated BLOCKED_RULE.
- DONE — Transfer core: separate service/database, anonymous marketplace, Owner-configured facility policy, capped listing/edit/withdraw, atomic acquisition, durable signed REST handoff, payment inbox/reconciliation, immutable original snapshot and immediate previous-holder QR invalidation. Six PostgreSQL Transfer tests, Booking handoff tests, Payment escrow test and live Gateway/Payment/RabbitMQ handoff pass. Escrow remains HELD_POLICY_BLOCKED; no payout policy is selected.
- DONE — Admin monitoring core: account search/detail, reasoned temporary/permanent lock/unlock, approved role changes, immediate session revocation, durable notices and append-only audit in Identity. Booking/Payment monitoring and source aggregates remain in their owning services; separate real Admin pages and Owner operational report pass build/tests and Gateway smoke. Owner application and additional Admin workflows remain separate pending capabilities.
- DONE — Additional facility review: Owner submission, frozen pending profile, immutable snapshots, supplement/resubmit/approve/reject, private legal documents, retained submitted originals, audited Admin reads/decisions, duplicate-address hints, public availability after approval and durable owner notices. Separate Owner review/document and Admin review routes call real APIs. First-facility Owner applications remain a separate pending workflow.
- DONE — Profile contact verification: separate contact route, safe own challenge metadata, email/phone OTP, resend cooldown and invalidated superseded codes. Existing contact stays current until successful verification; roles stay unchanged. Database/API tests, Gateway email/demo-SMS smoke and browser OTP submission pass.
- DONE — Discovery filters core: public court results combine Facility category/location, Schedule opening/pricing and actual Booking reservations. Date/time/price/radius/sort filters, real empty/error/loading states and dated links to court availability replace the prototype. Map presentation remains a separate UI capability.
- IN_PROGRESS — Frontend: replace prototype workflows incrementally with routes and typed API calls.
- IN_PROGRESS — Infrastructure: reproducible Compose, local-only controlled demo seed, build/test/runtime smoke scripts.

## Isolated BLOCKED_RULE items

- Refund eligibility/percentage: no default financial policy selected.
- Staff pricing permission: Owner-only implementation can proceed; disputed Staff write path disabled.
- RESOLVED — Staff capability catalog explicitly approved by user on 2026-10-05: BOOKING_READ, BOOKING_CREATE_COUNTER, BOOKING_CHECK_IN, BOOKING_COMPLETE, SCHEDULE_READ. Owner-assigned facility binding required.
- Group changes after contribution payment: unresolved mutation path disabled.
- Transfer escrow release: no automatic payout policy selected.
- Additional role/permission labels from detailed Admin UCs: do not add beyond approved CUSTOMER/OWNER/STAFF/ADMIN.

## Verification

- Baseline checkout: `0fa3181ba42e150c35fc63a07b42f8c435e1924a`.
- Verification commands and results will be recorded per milestone; no feature is DONE from code existence alone.
- Auth: `scripts/demo/smoke-auth.ps1` passes against real PostgreSQL, Gateway, Identity and Mailpit.
- Backend: full 63-test run passed (Gateway 9, Common 3, Identity 13, Facility 8, Schedule 4, Booking 13 including Discovery/monitoring/QR/handoff, Payment 7, Transfer 6), zero failures/errors/skips using dedicated test databases. All seven service images packaged successfully. Scheduler initial delay avoids executing expiry/outbox work before test dependencies are initialized.
- Frontend: typecheck, lint (zero warnings), production build pass including Transfer, Admin monitoring, Owner reports and facility review. Typecheck regenerates production route types before checking; interrupted dev-generated cache no longer contaminates CI checks. Browser verification found and corrected missing submit types on Group split/join forms; split/payment flow reverified to CONFIRMED.
- OpenAPI: Identity, Facility, Schedule, Booking/Group, Payment, Transfer and Admin/reporting validated with pinned generator v7.15.0. Facility policy and transfer-payment extensions revalidated.
- Admin: `smoke-admin.ps1` passes through Gateway with a separate controlled account: CUSTOMER 403, role changes/lock revoke all sessions, unlock requires a new login, Mailpit notices delivered, three reasoned audits, Booking detail without holder QR, Payment monitoring and source statistics, Owner report. Identity Flyway V4 applied; all six service databases match source migrations.
- Facility review: `smoke-facility-review.ps1` passes submission guards, supplement/resubmit, immutable original document snapshots, retained original MinIO access, approval/idempotency, discovery/availability and Facility→RabbitMQ→Identity→Mailpit notice. Browser Admin approval and audit history verified; controlled review courts disabled afterward. Flyway: Identity V5, Facility V6, Schedule V2, Booking V3, Payment V4, Transfer V1; all six databases verified against source with no failed migrations.
- Regression after facility review: Schedule, Booking/Payment, Group and Transfer Gateway smokes pass with explicit demo-center selection, independent of newly approved review fixtures.
- Profile: `smoke-profile-contact.ps1` passes own authenticated challenge metadata, resend cooldown, email/demo-SMS delivery, verification/replay protection, new login identifiers and unchanged roles. Browser contact page successfully verifies a controlled pending email and refreshes the displayed current contact. No schema migration required.
- Runtime: `smoke-booking-payment.ps1`, `smoke-staff-lifecycle.ps1` and `smoke-group.ps1` pass. Existing test-only five-minute court disabled after the completed lifecycle test.
- Transfer: `smoke-transfer.ps1` passes against real services and databases; browser Seller form → Buyer acquisition → demo payment → SUCCESS handoff → new holder booking/QR passes. Evidence saved in ignored `tmp/demo/transfer-confirmed.jpg`. The following Group smoke also passes after Transfer integration.
- Discovery: `smoke-discovery.ps1` passes anonymous Gateway results, combined category/location/price/time/radius filters, sorting, invalid request guards and safe public projections. Backend tests prove a real HELD reservation removes its slot, disabled/maintenance courts are excluded and upstream errors become 503. Booking/Facility contracts revalidated.
- Compose: all eight application images built; all 13 infrastructure/application containers healthy. Containerized frontend on 13000 calls Gateway on 18080 through the internal rewrite. Public links/CORS follow the frontend port; IPv4 health probe matches Node's bind address. Containerized Booking/Payment, Group, Transfer, Facility review, contact verification, Admin monitoring and migration smokes pass.
- Local infrastructure: isolated `sporthub-14b1` Compose project, healthy PostgreSQL/Redis/RabbitMQ/MinIO/Mailpit. Existing `sporthub` project untouched.
- Windows runtime: stop only this worktree's Java demo processes before rebuilding JARs; running JARs are locked by Windows.
