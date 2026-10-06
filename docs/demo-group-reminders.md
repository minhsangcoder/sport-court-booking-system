# Manual Group payment reminders (UC-6.7)

Source: read-only base-bussines.pdf, UC-6.5–6.7 pages 44–46 and NFR-7.1. This workflow sends notices and stores reminder metadata only. Refunds, financial mutations, post-payment split revisions and automatic scheduling are excluded.

Start the preserved demo from the worktree:

```powershell
./scripts/demo/start-apps.ps1 -SkipBuild
./scripts/demo/verify-migrations.ps1
./scripts/demo/smoke-group-reminders.ps1
```

Frontend: http://localhost:13000. Gateway: http://localhost:18080/api/v1. Mailpit: http://localhost:18025. Seed customer/customer2/owner/staff at @sporthub.local use the ignored local DEMO_PASSWORD. No password/token is stored in fixture output. Smoke logs out its own sessions and retains its CONFIRMED demonstration booking, as the existing Group smoke does.

For UI inspection, run smoke-group-reminders.ps1 -PrepareOnly (or -LeavePending to retain the exercised group). It writes nonsecret group/member IDs/deadline to ignored tmp/demo/group-reminder-fixture.json. Open /customer/groups/{groupId} as customer. These are real members/allocations with Staff's share paid through the existing DemoPaymentProvider; no direct fake financial rows. Complete the remaining demo contributions before the displayed deadline when finished. Preparation intentionally leaves a pending group for testing; it does not claim a completed smoke.

The payment section shows member shares/paid/remaining, deadline, paid/unpaid filter and reload. Booking Owner selects one/many unpaid other members or chooses all. Inline confirmation shows recipients, current remaining amounts and deadline. Confirm sends only selection IDs or all-mode, blocks duplicate clicks, displays per-member accepted/skipped feedback and reloads state. Paid/self/inactive/cooldown recipients are not notified. Ordinary members have no reminder actions; Facility Owner/Staff roles confer no override.

API: POST /api/v1/groups/{id}/payment-reminders with either {"memberIds":["member-uuid"]} or {"remindAll":true}. memberIds must be unique, non-null, 1..50; all plus any memberIds property is ambiguous. Bad selection 400; no valid session 401; wrong Booking Owner 403; missing Group 404; closed/unallocated Group 409; failed local enqueue 503. A successful 200 has acceptedCount, skippedCount and per-member outcome/reason/lastReminderAt/nextReminderAt. All-mode includes skip results for currently paid/cooldown other members; absent/inactive explicitly selected IDs produce NOT_MEMBER without exposing another group.

Current contribution model is GROUP_PENDING + booking PENDING, locked allocations, now strictly before deadline and booking end. CONFIRMED means fully collected; no separate after-confirmation contribution mode exists. Existing payment collects an entire unpaid share; no new partial-payment engine. The notice uses current due minus paid, currency and deadline/timezone resolved by Booking, never client payment fields. Payment, member changes, expiry and reminder acceptance share the same advisory transaction lock. A paid-race is skipped after the lock/read. Enqueue + last_reminder_at + safe history commit together; any batch enqueue failure rolls everything back.

Set GROUP_REMINDER_MIN_INTERVAL in the ignored .env, then recreate Booking with existing Compose conventions. application.yml property booking.group.payment-reminder.min-interval and Compose expose the same ISO-8601 duration. PT15M is an implementation default only; canonical source specifies configuration, no numerical policy. Minimum PT1S; invalid/zero/negative values fail startup. Server metadata controls the UI; no frontend interval constant. Immediate/concurrent retries accept once and return COOLDOWN/nextReminderAt for later requests. No Idempotency-Key or new dedup framework is required for this configured per-member window.

Success means the durable Booking outbox accepted the event. Booking V4 adds nullable group_members.last_reminder_at; existing event_outbox and booking_history retain event/time/actor/member. Identity consumes the existing RabbitMQ event envelope through a dedicated binding, validates producer/version/references and uses existing inbox/source-key dedup. Its own verified email or configured phone channel receives remaining VND amount, localized deadline and the current Group link. Existing IdentityNotificationJob retries SMTP failures, leaving PENDING until SENT; existing outbox retries broker failures. Terminal malformed/recipient failures use the existing DLQ. No new notification/retry provider or cross-service DB access.

Because delivery is asynchronous, accepted does not mean delivered. A payment after acceptance (or provider event still in transit) can change the live balance before delivery; the notice labels the acceptance snapshot and links to live status. A failed local enqueue shows an API/UI retry error and leaves no timestamp/cooldown. A later delivery failure uses existing operational retry, rather than creating another reminder or false delivery claim. No delivery-status endpoint is added.

Verified on 2026-10-06:

- 17 targeted new tests and full mvn -o package: 140 tests, zero failures/errors/skips. Fresh/upgrade V3->V4 preserve legacy obligations and null timestamp, Flyway validation/history pass. Config PT30S/PT2M override, auth, state/deadline boundary, UI/payment race, concurrent payment/duplicate requests, batch rollback/retry, notification content/dedup and SMTP recovery covered.
- pnpm lint/typecheck/build; booking OpenAPI validate/generate. Generated serializer checks preserve memberIds-only, memberIds+false and all=true. Request schema uses flat fields plus NOT validation so the generator cannot drop identifiers through oneOf branch guessing. Generated files stay ignored.
- Only changed Identity/Booking/frontend images built; subset Compose deployment, 13 healthy containers; migration verification I7/F8/S3/B4/P5/T1, zero failures.
- New reminder smoke, Booking-payment, Group, Owner Application and Facility Review regression smokes pass. Real notices, outbox dedup, unchanged finance fields and closed 409 checked.
- Browser one (1 accepted), multiple (2), all (2 accepted/1 paid skip), paid/cooldown race (0 accepted/2 skipped), payment filters/reload, ordinary member visibility, closed group controls and actual API 403/409. Six SENT notices across four browser groups match 1/2/2/1 outbox events; paid-race A has zero events. All four browser groups finalized via real DemoPayment, restored temporary seed display name, retained timestamp/history.
- No overflow at 360/390/1280 px with long synthetic name/email text; screenshots visually inspected. Browser console warn/error list empty. Ignored evidence: group-reminder-one.png, group-reminder-mobile-360.png, group-reminder-mobile-390.png, group-reminder-paid-race.png, group-reminder-completed.png and browser fixture metadata/logs under tmp/demo.

NOT VERIFIED: manual browser notification outage injection. Backend tests prove enqueue failure rolls back/retries and existing SMTP job recovers, but no live failure/retry UI result is claimed. No production load/device-delivery/real SMS guarantee. Overall Group persistence/chat/reuse and blocked financial capabilities remain incomplete.
