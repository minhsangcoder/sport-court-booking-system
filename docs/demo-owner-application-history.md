# Admin Owner application history

Canonical source: read-only `base-bussines.pdf`, UC-8.2/8.3/8.4 (pages 57–59) and NFR-3.1/3.6/4.1/4.4/6.4. The source precedence, naming mapping, scoped gaps and obsolete assumptions are recorded in `implementation-progress.md` Requirements source. UC-8.2 A-1 means cancel/back; no ZIP download is required or implemented.

Open `http://localhost:13000/admin/owner-applications` as the local demo Admin. Select a record from the real queue. Detail now shows the registering account's name, email/phone and status alongside the existing first facility, legal data and documents. The account summary is Admin-only; the applicant detail gets null. Viewing detail/history grants no role and changes no business state; protected reads remain uncached and audited.

The **Lịch sử thẩm định** tab shows paginated business events: creation, legal edits, submission, revision request, resubmission, requested Admin decisions and completed approval/rejection. Each recorded event shows timestamp in the facility timezone, current account name for its recorded actor UUID, recorded state transition/reason where available, changed legal field labels without their values, and links to the immutable submission cards. Invalid draft timezones fall back explicitly to UTC. Submission cards show the stored facility name and court/document counts. Existing protected original document links remain in the documents tab.

## Compatible API

`GET /api/v1/admin/owner-applications/{id}/history?page=0&size=20`

- Real ADMIN session required at the backend. Unknown application: 404. Invalid/non-integer/negative page or size outside 1–100: 400. Standard `ApiResponse` and `Cache-Control: no-store`.
- `data: {items, page, size, totalElements, totalPages}`; newest first, timestamp then UUID descending. Count/items use one repeatable-read snapshot; actor names use a join rather than per-event queries.
- Only application business events are projected. Detail/history access audits remain append-only but cannot displace business history. Older pages remain available beyond 100 business events.
- Projection never reads/decrypts private legal snapshots and never returns arbitrary audit JSON. State and changed-field names are whitelisted. Reason is the existing authorized decision note. Historical unknown state/payload fields are excluded.
- New important application audits use existing `old_value`/`new_value` columns for safe before/after state metadata. Edits record only changed field names; unchanged saves do not generate false revisions. Submissions record origin and immutable submission UUID; approval replay adds no second decision event.
- Existing list/detail/submission/audit shapes stay compatible; detail adds optional `applicant`. Persisted/wire `PENDING_APPROVAL` means canonical Owner `PENDING_REVIEW`; `SUPPLEMENT_REQUIRED` means `REQUIRE_REVISION`. No new synonymous state or migration.

## Limits

Older events retain their real actor/action/time and existing reason, but missing before/after state or changed-field metadata is explicitly labelled; it is not fabricated or backfilled. Actor names are current profiles, not historical name snapshots; recorded UUIDs retain attribution. Immutable legal snapshots remain encrypted and are not added to timeline responses.

The first-facility event section uses its existing latest-100 audit contract and says so explicitly. Full historical Facility field diffs and unbounded Facility audit access are not implemented by this Identity history slice. Existing returned submission snapshots remain unchanged. The combined Owner account-registration path (UC-1.7) remains a gap; contact email (UC-2.1) is completed in the section below; this is not a claim that every canonical onboarding requirement is DONE.

## Verification

From this worktree in PowerShell 7, preserve the ignored `.env` keys and use the isolated demo project:

```powershell
./scripts/demo/start-apps.ps1 -SkipBuild
./scripts/demo/verify-migrations.ps1
./scripts/demo/smoke-owner-application.ps1
./scripts/demo/smoke-owner-application-filters.ps1
./scripts/demo/smoke-admin.ps1
```

The onboarding smoke verifies real encrypted persistence, revision/edit/resubmit, locked applicant, review/approval/replay, session revocation/re-login, public facility, notification, minimal account summary, eight safe history events, paging, authorization and stored before/after audit. The approved fixture court is disabled afterward. Non-secret IDs are in ignored `tmp/demo/owner-history-fixture.json`.

Browser verification on 2026-10-06: real account/detail and eight-event approval timeline; edit field label without its value; both immutable submission cards; facility event section; a private revision fixture with 26 real API-generated events split 20+6 across two history pages without overlap; filtered queue/detail/return preserves keyword/status/page/size; legacy history explicitly marks five missing-metadata records. No browser error/warning logs. Queue/detail/history have no page horizontal overflow at 360 px; detail/history also verified at 390 px. A long synthetic applicant email exposed grid overflow at 360 px, fixed and rechecked. Ignored evidence: `owner-history-desktop.png`, `owner-history-360.png`, `owner-history-390.png`; pagination fixture IDs: `owner-history-paging-fixture.json`. Fixtures are synthetic, and the revision fixture remains Customer/private.

Full backend `mvn -o package`: 94 tests, no failures/errors/skips (88 existing + 6 history tests). Frontend `pnpm lint`, `pnpm typecheck`, `pnpm build`: PASS. Identity OpenAPI v1.4 validation/client regeneration: PASS, with the pre-existing unused AddressInput recommendation; new nullable fields generate correct null unions. Existing discovery smoke also PASS; all 13 Compose containers healthy. Only Identity/frontend images rebuilt/deployed; all demo migrations match I6/F7/S3/B3/P5/T1, with no failed history.

## UC-2.1 private facility contact email

The canonical form (PDF pages 23–24) now accepts **Email liên hệ cơ sở (tùy chọn)**. This is independent of **Email tài khoản**; it is never auto-filled from a credential. Enter a valid email or leave it blank. Backend trims/lowercases consistently with Identity and validates supplied email/max254; new Facility V8 is nullable for existing rows. Old PUT requests without this new property preserve the contact; explicit null/blank clears it. Existing-client create without email still works.

Owner onboarding and `/owner/facilities/new` use real API requests. Edit/supplement forms prefill the current private draft; save/reload reads persisted contact, cancel leaves it unchanged. Owner overview displays the operational contact. Guest facility/search/discovery/court context do not include contactEmail.

Admin **Cơ sở và cấu hình** uses `reviewSnapshot.facility.contactEmail`, the latest immutable submitted version, and never uses account/current operational email as fallback. Before a submission or on an old snapshot, it displays “Chưa có trong phiên bản hồ sơ này”. **Lịch sử thẩm định** shows each immutable submission's email and the safe changed-field label **Email liên hệ cơ sở**. Only field names are stored/displayed in edit audit events; no before/after email values are added to audit.

Run the existing onboarding smoke for initial contact persistence, V1 → requested revision → draft edit (current review still V1) → resubmit V2 → approve/replay → fresh Owner session → operational edit (review remains V2) → Guest privacy. It checks both Facility/Identity DB snapshots and existing lifecycle/history/Mailpit invariants, and disables the approved fixture court afterward. `smoke-facility.ps1` additionally checks ordinary Owner create/update/reload and preservation on a legacy PUT.

```powershell
./scripts/demo/smoke-owner-application.ps1
./scripts/demo/smoke-facility.ps1
./scripts/demo/smoke-facility-review.ps1
./scripts/demo/smoke-discovery.ps1
```

Legacy submissions are not backfilled; their absence of contact email and old diff metadata remains visible. Unbounded Facility audit and other historical field diffs remain separate work.

Contact-email verification on 2026-10-06: final full backend 103 tests (0 failure/error/skipped); targeted 10 plus final 4 Facility contact tests; frontend lint/typecheck/build; Facility/Identity/owner-internal OpenAPI validation/generation; isolated fresh/upgrade migrations and live I6/F8/S3/B3/P5/T1 history PASS. All six smokes above (including filters/Admin) PASS; all 13 Compose containers healthy. Only Identity/Facility/frontend rebuilt, with a final Facility-only rebuild for wrong-JSON-type validation. The Facility smoke's direct spoof-header probe now runs inside its isolated container instead of the removed host port 18082, and still requires 401.

Browser proof: real create/submit/revision/edit/resubmit with long contact emails, protected Admin V2/retained V1 and changed-field labels; neutral legacy snapshot; Owner ordinary create/reload/edit/reload/cancel; native validation and server-400 input retention; filtered-list return URL. Onboarding, Owner forms/overview, Admin queue/detail/history checked at 360/390px without page overflow. Saving an unmodified loaded edit form verifies prefill without exposing masked input values in automation logs. Evidence (ignored): `contact-email-history-360.png`, `contact-email-history-390.png`, `contact-email-admin-desktop.png`, `contact-email-ui-fixture.json`. The browser-created application remains pending/private for inspection; approved smoke fixture courts are disabled. Future wider Facility history/diffs and UC-1.7 signup remain separate work.
