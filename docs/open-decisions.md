# SportHub open decisions

This file records unresolved decisions that must not be silently encoded as business behavior.

## Refund policy

The primary thesis text states that a paid cancellation receives no refund, while the UC-5 specification and architecture blueprint describe time-based refund tiers. Phase 1 does not resolve this conflict. Booking and payment contracts must not be finalized until the product owner confirms the authoritative policy.

## Staff permission catalog

Resolved by explicit user approval on 2026-10-05: `BOOKING_READ`, `BOOKING_CREATE_COUNTER`, `BOOKING_CHECK_IN`, `BOOKING_COMPLETE`, `SCHEDULE_READ`. Owner grants each capability within a specific facility. Backend must require both an active binding and the matching permission. These names do not grant pricing write access.

## Owner application orchestration

The requirements treat the first facility profile as part of an Owner application, while target architecture assigns facility data to Facility Service. Before implementation, the team must decide whether Identity orchestrates the application, a dedicated application workflow owns it, or the services coordinate through an explicit saga. No cross-service database access is permitted.

## Identity role persistence

Resolved by the required multi-role implementation: Identity V2 introduces `user_roles` and profiles; V3 supports independent email/phone registration and verification. Existing V1 is preserved. Only CUSTOMER, OWNER, STAFF and ADMIN are used.

## MinIO Community Edition image provenance

MinIO Community Edition is now distributed upstream as source-only. Local Compose therefore uses a community automation that rebuilds the pinned upstream release, with the multi-architecture image itself pinned by digest. Before any production deployment, the team must choose and document a trusted supply path: build and sign the image internally, mirror a verified artifact in a controlled registry, or select an approved S3-compatible alternative.
