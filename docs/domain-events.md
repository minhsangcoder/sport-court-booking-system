# SportHub domain event conventions

This document records the technical envelope and implemented cross-service event contracts. Business payloads remain owned by their producer.

## Envelope

Every RabbitMQ domain event uses the following immutable envelope:

- `eventId`: globally unique UUID generated once by the producer.
- `eventType`: stable lower-case dot name such as `booking.created` or `payment.completed`.
- `eventVersion`: positive integer; the first published schema is version `1`.
- `occurredAt`: UTC instant describing when the domain fact occurred.
- `producer`: stable service name such as `booking-service`.
- `correlationId`: identifier propagated from the initiating request or message.
- `aggregateId`: UUID of the aggregate that emitted the event.
- `payload`: event-specific immutable data owned by the producing service.

The Java representation in `sporthub-common` defines only this technical envelope. Payload DTOs remain inside the owning service and must not be added to the common module.

## Naming and versioning

Event names use `{aggregate}.{past-tense-verb}` and describe facts that already happened. Names must remain stable after consumers depend on them. Commands, implementation details and environment names do not belong in `eventType`.

Compatible additions may keep the same `eventVersion` when existing consumers can safely ignore the new optional fields. Removing a field, changing its meaning or changing its type requires a new event version. Producers must not silently publish two incompatible payload shapes under the same event type and version.

## Publication

Each bounded context publishes to its durable topic exchange, using `eventType` as the routing key. A service should persist its state change and an outbox record in the same local transaction. Publishing from the outbox occurs after commit; direct broker publication from an uncommitted business transaction is not the target design.

## Correlation

HTTP requests enter through the API Gateway with `X-Correlation-Id`. Services copy that value into emitted events. Consumers preserve it when they emit follow-up events. If no correlation ID exists at an entry point, the entry component creates one.

Correlation IDs are observability metadata, not authentication or authorization evidence.

## Consumer idempotency

RabbitMQ delivery is treated as at-least-once. Every consumer must record successful processing using a unique key containing the consumer name and `eventId`. A repeated event with the same key must return the previously established outcome without repeating side effects.

Consumers must validate the envelope and supported version before processing the payload. Unknown event types or unsupported versions are not silently accepted.

## Retry and dead-letter handling

Transient failures such as a temporary database or network outage may be retried with bounded exponential backoff and jitter. Retry count and delay must be explicit per consumer.

Validation failures, unsupported versions and deterministic business rejections are not transient and should not loop through retries. After the retry budget is exhausted, the message is routed to a consumer-specific dead-letter queue with the original event metadata and failure reason preserved.

Dead-letter messages require observable alerts and an explicit replay procedure. Replay must pass through the same idempotency check as normal delivery.

## Implemented payment integration

`payment.completed` v1 is produced by Payment Service after a verified provider callback. Its payload contains `paymentId`, `bookingId`, `payerId`, nullable `memberId` and `acquisitionId`, `purpose` (`BOOKING`, `GROUP_CONTRIBUTION` or `TRANSFER`), `amount`, `currency`, `status`, `paidAt`, and `transactionId`. Booking validates individual/group outcomes in its court-locked inbox and ignores Transfer outcomes. Transfer validates its own acquisition, buyer, amount and deadline through a separate inbox. A mismatch records reconciliation without granting usage rights.

`booking.group.refund.requested` v1 records that a group contribution requires refund review after timeout or a rejected late/duplicate payment. Its payload contains `bookingId`, `paymentId`, the original `payerId`, `reason`, and `policyState=BLOCKED_RULE`. Payment consumes it through its own inbox and persists an idempotent refund request. It does not choose a refund percentage or execute financial movement while the policy is unresolved.

Both services use durable queues with dead-letter routing and three bounded delivery attempts. Outbox publication waits for a correlated broker confirmation and checks mandatory returns before marking an event published. Repeated event IDs and repeated provider transactions cannot repeat the domain side effects.

## Transfer handoff and escrow

Transfer persists REGISTER/EDIT/LOCK/UNLOCK/WITHDRAW/HANDOFF commands before calling Booking's signed private REST endpoints. Booking owns the transfer lease, serializes it with check-in under the court lock, and changes holder/customer plus QR version in one local transaction. REST retries use listing/acquisition/payment identities; a lost response cannot hand off twice. A transient error leaves the command durable for retry. A deterministic handoff rejection becomes PENDING_AUDIT.

`transfer.refund.review.requested` v1 contains `paymentId`, `bookingId`, the original `payerId`, and `reason`. Payment validates the referenced successful Transfer order, persists a BLOCKED_RULE refund request through its inbox, and marks escrow REFUND_REVIEW. Successful Transfer payments create an immutable amount/payer/seller escrow record in HELD_POLICY_BLOCKED. No payout or refund amount is selected while release/eligibility rules remain unresolved.
