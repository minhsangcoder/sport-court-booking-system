## ADDED Requirements

### Requirement: Initial facility payment uses SePay and verified incoming money
New REAL booking and group contribution orders SHALL create a private QR SePay payable to the facility's immutable bank receiver revision. The server SHALL determine amount, currency, unique short memo and expiry. A QR image, redirect, timer, screenshot or simulation SHALL NOT mark a booking paid.

#### Scenario: Private booking QR
- **WHEN** a customer creates a payable order for a REAL booking
- **THEN** the QR contains the facility receiver and exact server amount/memo; the booking remains unpaid

### Requirement: Facility webhook secrets stay private
The current facility Owner SHALL configure bank BIN, account number, account name and webhook API key. The server SHALL encrypt the secret and account, accept the key only as write-only input and return only masked account metadata on configuration reads.

#### Scenario: Reading facility configuration
- **WHEN** an authorized Owner reads the configured channel
- **THEN** the response contains no webhook API key or full account number

#### Scenario: Owner configures the first receiving account
- **WHEN** the authorized Owner opens the channel page before any channel exists
- **THEN** GET returns HTTP204, the UI shows an unconfigured state with an enabled entry form, and the first configuration can be saved without bypassing facility ownership checks

### Requirement: Initial bookings do not require a Customer bank account
Initial booking and group contribution eligibility SHALL depend on the facility's receiving channel, not a personal bank destination of the paying Customer. Missing-channel errors SHALL identify the facility account that its Owner must configure.

#### Scenario: Customer pays without registering a personal bank
- **WHEN** an eligible Customer without a personal bank destination books at a facility with a ready SePay channel
- **THEN** the Customer can hold, book and pay the facility; full verified incoming money confirms the booking without registering a Customer bank

### Requirement: Incoming SePay notices commit validated bank evidence once
The webhook SHALL authenticate the exact channel/receiver revision, match account/bank/memo/incoming direction, deduplicate SePay IDs and bank references, and commit bank evidence, canonical allocation and outbox atomically. Partial or unverified money SHALL NOT grant usage.

#### Scenario: Duplicate partial and full receipts
- **WHEN** a valid incoming webhook supplies 40,000 of 100,000 VND twice, then another valid transaction supplies 60,000 twice
- **THEN** there are two bank credits and one canonical 100,000 collection; no usage is granted at 40,000

#### Scenario: Wrong receiver or authentication
- **WHEN** a notice has a wrong secret, account, bank, ambiguous memo or conflicting transaction identity
- **THEN** it cannot certify payment or grant court usage; a binding conflict is visible for review

#### Scenario: Expired, partial or excess payment
- **WHEN** money cannot be applied to the original unpaid obligation before its deadline, or exceeds its amount
- **THEN** it is tracked as unapplied money for operational reconciliation, not additional booking revenue or a P2P refund

### Requirement: New customer workflows cannot create the former fee model
New listings, edited prices and acquisitions SHALL only use PEER_RESALE_V1 on an eligible REAL booking. B SHALL pay the full agreed P directly to A with QR SePay, without facility fees or Owner payout obligations. DEMO and legacy bookings SHALL remain historical and SHALL NOT be converted to REAL funding. Previously opened legacy transactions and existing debts SHALL remain readable and recoverable.

#### Scenario: Customer opens an old DEMO booking's transfer route
- **WHEN** the old transfer creation route is opened
- **THEN** no fee quote, new legacy order or payable QR is offered; the UI explains that a paid REAL booking is required

### Requirement: Paid bookings have no direct customer cancellation action
Customer direct cancellation SHALL only release an unpaid live hold or pending booking. A confirmed paid booking SHALL retain its usage right and SHALL NOT offer or accept a self-cancellation without refund. Eligible operational refunds, historical cancellations and unpaid timeout/hold release SHALL remain separate.

#### Scenario: Customer calls the old paid cancellation API
- **WHEN** a customer sends a cancellation directly for a confirmed DEMO or REAL booking
- **THEN** the request is rejected without changing booking state, reservation or history
