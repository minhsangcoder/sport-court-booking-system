
## ADDED Requirements

### Requirement: Counter cash receipts
The system SHALL let the authorized original counter operator record full cash receipt for a pending REAL counter booking, using a distinct immutable CASH receipt and current facility permissions.

#### Scenario: Cash replaces an unpaid bank request
- **WHEN** the operator confirms receipt before expiry and no bank credit or settled obligation exists
- **THEN** the system SHALL settle once with CASH_RECEIVED, expire the old QR request, and preserve later bank transfers for reconciliation without double application

#### Scenario: Retry and unsafe collection
- **WHEN** a cash request is repeated, or an unauthorized operator, expired booking or partially bank-funded booking is submitted
- **THEN** the system SHALL replay the existing authorized receipt or reject the invalid request without another receipt
