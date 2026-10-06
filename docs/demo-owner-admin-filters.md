# Owner application review filters

Admin queue: `http://localhost:13000/admin/owner-applications`. Sign in with the local demo Admin; the password remains in the ignored `.env`.

The queue defaults to `PENDING_APPROVAL`, oldest submission first. Choose All to search every state. Apply and Reset return to the first page; Reset restores the default pending queue. Applied filters, sort, page and size are bookmarkable URL parameters. Detail links carry a validated relative return URL so the review page returns to the same filtered page. A decision can remove a record from the current filter; an empty/out-of-range page offers a first-page link.

## API

`GET /api/v1/admin/owner-applications/page` requires a real active Admin session. The response keeps the normal `ApiResponse` envelope, with `data: {items, page, size, totalElements, totalPages}`. Each item is the existing safe application summary. The original `/admin/owner-applications?q=&state=` endpoint still returns an array of at most 100 summaries.

Optional filters combine with AND:

- `q`: business name, registered facility name, account full name or account phone (the existing keyword semantics).
- `state`: one of the existing eight Owner application states; omitted/blank means all.
- `applicant`: account full name, email or phone substring.
- `facility`: first-facility name stored on the application, refreshed at submission. This is not a live lookup into Facility's database.
- `submittedFrom`, `submittedTo`: latest submission date.
- `reviewedFrom`, `reviewedTo`: completed decision date. Null timestamps do not match date filters.
- `reviewedBy`: exact Admin UUID assigned to the latest requested decision. Resubmission clears reviewer/date.
- `applicationId`: exact UUID.
- `sort`: `SUBMITTED_ASC` (default) or `SUBMITTED_DESC`; each also sorts by creation time and UUID for ties, with null submission times last.
- `page`: zero-based, default 0; `size`: 1–100, default 20. Out-of-range pages retain the true count and return empty items.

Text is trimmed and blank input is absent. SQL wildcard characters match literally. Dates are `YYYY-MM-DD`, inclusive Vietnam calendar days (UTC+7); the query uses an exclusive next-day upper bound. Each from must precede or equal its to. Invalid enums, dates, ranges, UUIDs, sort, lengths or pagination return the existing 400 `IDENTITY-OWNER-APPLICATION` error envelope. Count and items use the same read-only repeatable-read transaction; the query selects no encrypted payload and avoids per-record lookups. No schema migration is needed.

## Security limitation

Canonical `base-bussines.pdf` UC-8.2 requires status filtering and protected detail, not representative-name search. Extended filters are useful implementation enhancements. That name is encrypted with the other legal data and is different from the plaintext account full name. Search supports existing account fields; it does not decrypt the queue, scan ciphertext as plaintext, create plaintext copies or expose legal data in summary responses. Authorized audited detail views still display the legal representative. Encrypted-name search needs a separate approved security/search design if required later.

## Verify and prepare private fixtures

From the worktree using PowerShell 7:

```powershell
./scripts/demo/smoke-owner-application-filters.ps1
./scripts/demo/smoke-admin.ps1
./scripts/demo/verify-migrations.ps1
```

The filter smoke creates three verified synthetic Customers and real private first-facility applications via existing APIs/fixtures, then submits them under one unique business keyword. Courts, hours, prices and documents use the existing onboarding fixture. It exercises both API contracts, filters/AND, date inclusion, paging/order, invalid parameters, real Admin authorization, safe summaries and detail access. It never approves/publishes these facilities or changes existing business records. The ignored `tmp/demo/admin-owner-filter-fixtures.json` contains non-secret fixture identifiers, keyword and submitted dates for browser checks; it contains no tokens/password/key/legal data.

Use that keyword to check Apply, status, facility, dates, AND, two records per page, retained pagination filters, Reset and a detail round trip. A supplement decision on a synthetic fixture should move it out of the pending filter without losing the return URL. Backend PostgreSQL tests cover exact midnight bounds and tied-date pagination independently of fixture timing. No frontend test framework has been added.
