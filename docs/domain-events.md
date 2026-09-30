# SportHub domain event conventions

This document defines the technical contract for asynchronous communication between SportHub services. It does not define business events or implement event-driven workflows.

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

## Scope of Phase 1

Phase 1 establishes the envelope and conventions only. It does not publish `booking.created`, `payment.completed` or any other business event. Queue topology, retry values and payload contracts are introduced with the owning service in later phases.
