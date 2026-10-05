# SportHub open decisions

This file records unresolved decisions that must not be silently encoded as business behavior.

## Refund policy

The primary thesis text states that a paid cancellation receives no refund, while the UC-5 specification and architecture blueprint describe time-based refund tiers. Financial cancellation/refund execution remains BLOCKED_RULE. Core booking/payment/group/transfer contracts document the implemented flow and durable requests for review; they do not select a refund percentage.

## Staff permission catalog

Resolved by explicit user approval on 2026-10-05: `BOOKING_READ`, `BOOKING_CREATE_COUNTER`, `BOOKING_CHECK_IN`, `BOOKING_COMPLETE`, `SCHEDULE_READ`. Owner grants each capability within a specific facility. Backend must require both an active binding and the matching permission. These names do not grant pricing write access.

## Owner application orchestration

Resolved technically for UC 8.2–8.4: Identity owns a durable, leased coordinator. Signed private REST commands prepare the first Facility and a zero-balance Payment wallet idempotently. Identity's final transaction grants OWNER, stores APPROVED, revokes old sessions and records the decision/notification. Facility publication and privileged writes check the committed application through Identity's private API. Prepared resources remain private if a dependency fails or the applicant is locked; retries resume without repeating the decision. Schedule serializes configuration snapshots and writes with its own database lock. Each service reads only its own database. Commission is an explicit Admin input; this does not resolve settlement/refund policies.

## Admin account hierarchy

Account controls use the approved ADMIN role. A reasoned change to ordinary CUSTOMER/OWNER/STAFF accounts revokes all sessions and records immutable before/after audit. Self changes and changes to an existing ADMIN target are rejected. Detailed Root Admin/equal-rank rules remain BLOCKED_RULE until that hierarchy is approved; no extra role labels are introduced. Read-only monitoring/reporting can proceed.

## Identity role persistence

Resolved by the required multi-role implementation: Identity V2 introduces `user_roles` and profiles; V3 supports independent email/phone registration and verification. Existing V1 is preserved. Only CUSTOMER, OWNER, STAFF and ADMIN are used.

## Transfer policy and settlement

Facility V3 stores explicit Owner-configured enablement and minimum lead time per facility; an unconfigured facility cannot create/acquire a Transfer listing. Individual Transfer usage rights are implemented through a durable Transfer-to-Booking REST saga. Payment records escrow as HELD_POLICY_BLOCKED because sources disagree on release after handoff versus after play. No automatic seller payout occurs. Paid Group ownership/membership changes remain BLOCKED_RULE and are rejected before listing creation.

## MinIO Community Edition image provenance

MinIO Community Edition is now distributed upstream as source-only. Local Compose therefore uses a community automation that rebuilds the pinned upstream release, with the multi-architecture image itself pinned by digest. Before any production deployment, the team must choose and document a trusted supply path: build and sign the image internally, mirror a verified artifact in a controlled registry, or select an approved S3-compatible alternative.
