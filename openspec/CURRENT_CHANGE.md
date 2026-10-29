# Current proposal — single authority

Current change: `add-flexible-booking-and-peer-resale`
Proposal: `openspec/changes/add-flexible-booking-and-peer-resale/proposal.md`
Selection confirmed by the user: 2026-10-27.

Latest business revision: section 33 (2026-10-30): a booking is any continuous range from 30 minutes
with no upper limit (replaces 60–240 minutes; no more orphan-gap warnings); search shows the Owner's
hourly price; the facility manual payout/refund workflow is removed (UI, `/bookings/facility-finance/*`,
booking migration V21; obligations/receipts stay read-only history); receipts whose CASH/BANK evidence Payment cannot return at report time
stay out of ALL; login 1-tap demo accounts removed; booking detail redesigned. Open decision 33.4:
how to settle unpaid facility obligations that still lock the payee's bank changes.

Earlier business revision: section 32 (2026-10-29): report source ALL/CASH/BANK uses verified REAL receipts; remove DEMO selector and disbursement link.

Earlier business revision: section 31 (2026-10-29): Owner revenue displays court collections only; peer section displays completed handoffs and open usage cases only.

Earlier business revision: section 30 (2026-10-29): simplify Owner bank and peer reporting to bank collections and usage-right status only.

Earlier business revision: section 29 (2026-10-29): remove Owner refund-policy configuration across frontend, backend and database; new holds, bookings and peer listings require no refund policy. Remove policy-refund report widgets. Restyle court availability with Home components and tokens. Preserve accepted historical financial evidence and technical reconciliation.

Earlier business revision: section 28 (2026-10-29): counter bookings support explicit
cash receipts by the original operator with current facility permissions. Cash
is REAL but never bank-verified; QR payments remain available. Reject cash after
partial bank funding or expiry; late transfers cannot settle the obligation twice.

Earlier business revision: section 27 (2026-10-28): explicit start/end selection,
live free ranges, proportional pricing from the existing slot duration for new
quotes (user confirmed), and group creation at least 15 minutes before play.
Group payment closes at min(created + 30 minutes, startsAt - 5 minutes).
The organizer may pay unpaid member shares, including replacing an unpaid SePay
QR; duplicate credits remain reconciled, never counted twice. Existing accepted
bookings and amounts are unchanged.

Earlier business revision: proposal + tasks sections 21–26 (2026-10-28): SePay for new
facility collections with authenticated sufficient-money webhooks; SePay B→A P2P
without facility fees; no Customer self-cancellation of paid bookings. Old DEMO
records do not become REAL funding. Read this revision before applying older
completed task descriptions inside the current change.
Customer bank registration is required only for the seller publishing a resale,
not for an initial booking or resale purchase. Owner configures the facility's
receiving channel separately; first-time setup must remain usable.
Customer screens no longer show facility payout/refund dashboards, including the
DEMO equivalent. Existing financial obligations and operational records remain.
Only the current Customer holder can read the ten-character check-in code or
signed booking QR. Owner/Staff consume credentials presented by that Customer;
operational APIs never issue them. Counter guests use scoped attendance without
an exposed code. Owner/Staff cannot cancel Customer bookings under the removed
facility-refund policy; old quote/cancel endpoints and rejection stubs are deleted (HTTP404).
An Owner cannot create a personal booking at their own facility, including through
an existing hold, group creation or linking themselves as the counter Customer.
Owner counter booking for other Customers/walk-in guests and scoped check-in remain
available through explicit Owner navigation. Personal booking elsewhere is allowed.
Latest cleanup (section 26): show the QR and ten-character manual code without the
raw signed token below it. Delete unused fee-based transfer creation/quote/edit/
acquisition code; retain live historical reconciliation, readers and guards.
Historical test fixtures must not call removed production creation flows.

## Mandatory selection rule

Applies to all OpenSpec and Ponytail skills, implementation, fixes and reviews
in Codex, Antigravity and other IDEs working in this project.

1. The user's latest explicit instruction takes precedence. Otherwise use only
   the current proposal above and design/specs/tasks inside that same change.
2. Older proposals, their specs/tasks, archived documents, main specs derived
   from older changes and old chat memory are NOT additional requirements.
   Do not read them to fill gaps, merge their rules or resume their unfinished tasks.
   Historical investigation is allowed only when the user requests it; history
   still cannot override current intent.
3. If current artifacts disagree, the current proposal sets the intent. Report
   the inconsistency and reconcile within the authorized task; ask only if the
   latest intent is materially ambiguous. Never resolve it by importing old rules.
4. Current code/tests are evidence of implementation, not authority for business
   intent. Legacy behavior is retained only as explicitly scoped by this proposal
   or the user. For example, its DEMO/history exception does not reinstate Owner
   fees or Owner payouts for new REAL P2P transactions.
5. This is a selection rule, not an instruction to delete existing features,
   migrations or data outside the task. Missing scope is not a request to remove it.
6. Announce the current change at the start. If it is complete, report that fact
   or perform the requested review/fix; do not select an older unfinished change.
   If the pointer or required artifacts are missing, stop dependent work and
   explain the problem rather than guessing from timestamps.

## When generating or updating a proposal

- When a new proposal is written, update this file to its exact change/path and
  mark the previous change's Markdown artifacts HISTORY ONLY. Do this in the same
  task, before handoff. Do not rely on directory mtime or `lastModified` sorting.
- A newly selected proposal with incomplete design/specs/tasks is not ready to
  apply. Finish its planning as authorized; do not fall back to the previous one.
- Updating the current proposal keeps the same pointer; reconcile its own
  artifacts. Never copy old requirements merely because a section is missing.
- Sync/archive only when requested. Never union older deltas into current specs.
  Archiving the current change must update its path here and cannot reactivate an
  old change. A user must explicitly request a switch to a historical proposal.

## History only — excluded from apply/review requirements

- `enable-real-payments-and-vietqr-payouts`
- `refactor-modules-and-improve-loading`
- `fix-owner-reports-and-revenue`
- `fix-counter-booking-ownership`

Their task counts record history, not permission to continue those plans.
