# SportHub open decisions

This file records unresolved decisions that must not be silently encoded as business behavior.

## Refund policy

The primary thesis text states that a paid cancellation receives no refund, while the UC-5 specification and architecture blueprint describe time-based refund tiers. Phase 1 does not resolve this conflict. Booking and payment contracts must not be finalized until the product owner confirms the authoritative policy.

## Staff permission catalog

Requirements confirm facility-scoped Staff permissions but do not define one final stable vocabulary. The Identity contract therefore validates the permission-name format without inventing a closed enum. The permission catalog and authorization matrix must be approved before Phase 2 authorization is implemented.

## Owner application orchestration

The requirements treat the first facility profile as part of an Owner application, while target architecture assigns facility data to Facility Service. Before implementation, the team must decide whether Identity orchestrates the application, a dedicated application workflow owns it, or the services coordinate through an explicit saga. No cross-service database access is permitted.

## Identity role persistence

The current V1 migration stores one `role` value on `users`, while requirements and the public contract allow an account to hold multiple roles. Phase 2 must either introduce a service-owned role-assignment table through a new migration or explicitly narrow the contract after product confirmation. Existing committed migrations must not be edited after they have been applied.

## MinIO Community Edition image provenance

MinIO Community Edition is now distributed upstream as source-only. Local Compose therefore uses a community automation that rebuilds the pinned upstream release, with the multi-architecture image itself pinned by digest. Before any production deployment, the team must choose and document a trusted supply path: build and sign the image internally, mirror a verified artifact in a controlled registry, or select an approved S3-compatible alternative.
