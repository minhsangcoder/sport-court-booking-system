# SportHub open decisions

This file records unresolved decisions that must not be silently encoded as business behavior. `base-bussines.pdf` supersedes older source assumptions; see `implementation-progress.md` Requirements source for the canonical reconciliation.

## Refund policy

Canonical FR-5.4/UC-6.8 require eligible per-payment refunds, at-most-once execution, retained history and possible manual Admin review. NFR-7.1 requires configurable refund percentages. Exact policy defaults, tiers and financial execution/accounting remain BLOCKED_RULE; old-source no-refund versus fixed-tier contradictions are obsolete. Existing durable requests do not execute a financial policy.

## Staff permission catalog

Resolved by explicit user approval on 2026-10-05: `BOOKING_READ`, `BOOKING_CREATE_COUNTER`, `BOOKING_CHECK_IN`, `BOOKING_COMPLETE`, `SCHEDULE_READ`. Owner grants each capability within a specific facility. Backend must require both an active binding and the matching permission. These names do not grant pricing write access.

## Delegated Staff facility editing (UC-2.2)

Canonical UC-2.2 names Owner and authorized Staff, with facility management permission required. The explicitly approved Staff catalog above has no facility-edit capability. Owner location editing is implemented; Staff without such a capability continues to receive 403. Delegated facility editing remains PARTIAL/BLOCKED_RULE until an explicit compatible capability is agreed. Do not use generic STAFF membership or BOOKING/SCHEDULE permissions as a facility-write bypass. This does not change the resolved Owner-only pricing rule.

## Owner application orchestration

Resolved technically for UC 8.2–8.4: Identity owns a durable, leased coordinator. Signed private REST commands prepare the first Facility and a zero-balance Payment wallet idempotently. Identity's final transaction grants OWNER, stores APPROVED, revokes old sessions and records the decision/notification. Facility publication and privileged writes check the committed application through Identity's private API. Prepared resources remain private if a dependency fails or the applicant is locked; retries resume without repeating the decision. Schedule serializes configuration snapshots and writes with its own database lock. Each service reads only its own database. Commission is an explicit Admin input; this does not resolve settlement/refund policies.

## Admin account hierarchy

Account controls use the approved ADMIN role. A reasoned change to ordinary CUSTOMER/OWNER/STAFF accounts revokes all sessions and records immutable before/after audit. Self changes and changes to an existing ADMIN target are rejected. The canonical PDF defines no Root Admin/equal-rank hierarchy; extra hierarchy rules remain BLOCKED_RULE, no extra role labels are introduced. Read-only monitoring/reporting can proceed.

## Identity role persistence

Resolved by the required multi-role implementation: Identity V2 introduces `user_roles` and profiles; V3 supports independent email/phone registration and verification. Existing V1 is preserved. Only CUSTOMER, OWNER, STAFF and ADMIN are used.

## Transfer policy and settlement

Facility V3 stores explicit Owner-configured enablement and minimum lead time per facility; an unconfigured facility cannot create/acquire a Transfer listing. Individual Transfer usage rights are implemented through a durable Transfer-to-Booking REST saga. Canonical UC-7.5 BR-2/UC-7.6 require holding buyer funds until successful handoff and accounting seller proceeds after handoff according to platform financial policy; after-play timing from old sources is obsolete. Payment still records HELD_POLICY_BLOCKED: settlement/accounting implementation is incomplete, and commission/payout/dispute details remain BLOCKED_RULE. No automatic seller payout occurs. Canonical UC-6.4 permits confirmed paid split revisions with member notices; payment delta/refund mechanics remain blocked. Paid Group membership/Transfer ownership changes require separate rules and are rejected before listing creation. No financial implementation is part of the Owner-history milestone.

## MinIO Community Edition image provenance

MinIO Community Edition is now distributed upstream as source-only. Local Compose therefore uses a community automation that rebuilds the pinned upstream release, with the multi-architecture image itself pinned by digest. Before any production deployment, the team must choose and document a trusted supply path: build and sign the image internally, mirror a verified artifact in a controlled registry, or select an approved S3-compatible alternative.

## Staff pricing source conflict

Canonical UC-3.3/UC-3.4 BR-1 restrict pricing writes to Owner and permit Staff read-only access. This specific rule overrides broad Owner/Staff FR wording and the UC-3.2 delegated-pricing phrase. Current Owner-only pricing writes are compatible; no Staff write permission is added.

## UC-1.7 registration and account verification

Resolved within the canonical/current lifecycle: registration can create a linked first-facility application and enter PENDING_APPROVAL before account verification. UC-1.1/1.2 account remains PENDING_VERIFICATION with no roles; only contact verification activates CUSTOMER. Existing login/edit and both approval/final-activation guards require verified ACTIVE account. Revision/rejection can notify the registered contact before verification; neither grants Owner. This preserves verification rules rather than introducing a separate activation path. No unresolved verification timing decision blocks this slice.

UC-1.7 versus UC-2.1 logged-in precondition is interpreted narrowly: first facility is created by server registration orchestration using signed private service commands. Generic Guest Owner/Facility/document APIs stay closed. UC-2.3 applicant-versus-Owner wording and Staff capability/pricing conflicts remain unchanged.

Registration failure/retry is technically resolved using the existing coordinator: Identity account/application/receipt commit locally; downstream setup failure safely retains pending account/private DRAFT, recoverable by identical multipart retry or existing verified workspace. No document bytes/password are kept for background setup recovery. Only existing SUBMITTING/decision operations retry automatically. Current verification expiry/recovery behavior is retained; receipt retention/OTP redesign is outside this task.

UC-1.7 verification completed on 2026-10-06: 123 backend tests, frontend/contract/migration checks, real restart/idempotency signup smoke, seven regression smokes and browser registration/verification/revision/approval/publication pass. A reproduced JVM map-order digest defect was corrected with deterministic serialization and compatible normalization of the twelve early WIP layouts. Missing-facility authenticated workspace recovery is integration-tested; manual downstream outage injection and recovery UI remain unverified. This limitation adds no business rule or public Guest API.
