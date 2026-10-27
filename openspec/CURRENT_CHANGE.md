# Current proposal — single authority

Current change: `add-flexible-booking-and-peer-resale`
Proposal: `openspec/changes/add-flexible-booking-and-peer-resale/proposal.md`
Selection confirmed by the user: 2026-10-27.

Latest business revision: proposal + tasks section 21 (2026-10-27): SePay for new
facility collections with authenticated sufficient-money webhooks; SePay B→A P2P
without facility fees; no Customer self-cancellation of paid bookings. Old DEMO
records do not become REAL funding. Read this revision before applying older
completed task descriptions inside the current change.

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
