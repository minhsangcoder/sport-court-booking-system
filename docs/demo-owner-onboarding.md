# Owner onboarding demo

Use the isolated Compose project configured by this worktree's ignored .env. Do not copy keys into source or change another project's volumes.

## Start the verified images

From the repository root:

```powershell
./scripts/demo/init-env.ps1
./scripts/demo/start-apps.ps1 -SkipBuild
./scripts/demo/verify-migrations.ps1
./scripts/demo/smoke-owner-application.ps1
```

Frontend: http://localhost:13000. Gateway: http://localhost:18080. Mailpit: http://localhost:18025. Seed Admin login is admin@sporthub.local; its password is the local DEMO_PASSWORD value, never a checked-in password.

init-env preserves existing configuration and generates a missing OWNER_APPLICATION_DATA_KEY locally from 32 random bytes encoded as Base64. Compose requires this key. Host execution rejects protected application operations safely if the key is absent/invalid. Preserve the key alongside the database backup: replacing it prevents decryption of existing applications. No real key is printed or committed.

## Browser scenario

Register and verify a Customer via Mailpit, or run smoke-owner-application.ps1 -RegisterOnly to create a controlled verified Customer (identifier printed, uses local DEMO_PASSWORD). Open /owner/apply; create a draft with synthetic legal/bank data and the first facility's address/GPS. The returned /owner/application/{id} page links to separate legal, courts, hours, pricing, documents and images pages. Add an enabled court, priced operating slots, two review documents and an image, then confirm submission.

Sign in as Admin and open /admin/owner-applications. Inspect detail, legal fields, private documents and the submitted schedule/price snapshot. Approve with an explicit commission percentage and reason, or request supplement/reject with at least 20 trimmed characters. Processing states can be retried without a duplicate approval. A locked/unverified applicant cannot finish activation.

After approval, sign in as the applicant again: old sessions are revoked. The Owner portal lists the first ACTIVE facility. Public discovery and privileged configuration only open once Identity's approval transaction commits. Existing Staff scope/read permissions continue to apply.

The smoke also checks supplement/resubmission, duplicate submission/approval, applicant ownership, Admin authorization, actual encrypted persistence, zero-balance VND wallet, audit and Mailpit notification. It disables its explicitly named test court at the end. Preparation modes -LeaveDraft and -LeavePending leave controlled test data for manual inspection; do not use them with real legal documents.

## Validation

Full backend package ran 76 tests using the dedicated test PostgreSQL on 15439. Four migration tests each install into a fresh schema and upgrade from the previous source version, then validate Flyway and zero failed history. Demo history is Identity V6, Facility V7, Schedule V3, Booking V3, Payment V5, Transfer V1.

Frontend lint/typecheck/build and pinned OpenAPI validation pass. Refund execution, paid Group mutations, escrow release/settlement and Staff pricing writes retain the existing BLOCKED_RULE boundaries. Owner wallet initialization does not introduce payouts or a settlement policy.
